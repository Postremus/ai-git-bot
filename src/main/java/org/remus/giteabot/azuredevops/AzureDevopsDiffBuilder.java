package org.remus.giteabot.azuredevops;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders unified diffs for Azure DevOps, which — unlike Gitea, GitHub, GitLab, and
 * Bitbucket — exposes no endpoint returning patch text. Change entries and blob contents
 * are fetched separately by {@link AzureDevopsApiClient} and diffed here.
 * <p>
 * The algorithm is a classic dynamic-programming LCS, hand-rolled to avoid adding a
 * dependency for it.
 * <p>
 * Memory is bounded by a cell budget. Below 4M table cells the full {@code int[m][n]}
 * table is used (~16 MB worst case); above it {@code coarseDiff} trims the common prefix
 * and suffix and replaces the middle wholesale, producing a valid if less granular diff.
 * The budget is reached at roughly 2000 lines per side, so ordinary large source files do
 * fall back — see {@link #coarseDiff} for what that costs.
 * <p>
 * Hunks match {@code git diff --no-color -U3}: same {@code @@} ranges, same context,
 * same {@code \ No newline at end of file} markers. The surrounding file headers are
 * reduced to {@code diff --git} plus {@code ---}/{@code +++} — there is no
 * {@code index}, {@code new file mode} or {@code deleted file mode} line. An absent
 * side is named {@code /dev/null} as git names it, which is what tells consumers
 * ({@code DiffSummary}, {@code ChangedFileContentsEnricher}) that a file was added or
 * deleted rather than merely changed; the {@code diff --git} header keeps the real path
 * on both sides, again as git does.
 */
public final class AzureDevopsDiffBuilder {

    /** Lines of unchanged context emitted around each change, matching git's default. */
    public static final int CONTEXT = 3;

    /** Maximum number of files rendered into a single pull-request diff. */
    public static final int MAX_FILES = 300;

    /** Maximum size of a single blob fetched for diffing. */
    public static final int MAX_BLOB_BYTES = 512 * 1024;

    private AzureDevopsDiffBuilder() {
        // utility
    }

    /**
     * Renders the complete {@code diff --git} block for one file, naming each side
     * separately so a file that was moved <em>and</em> modified in the same pull request
     * shows both its old and its new path, as {@code git diff} does.
     * <p>
     * A {@code null} path marks a side that does not exist — an added file has no old
     * side, a deleted one no new side — and is rendered as {@code /dev/null}. That is
     * the only signal consumers have: {@code ChangedFileContentsEnricher} would
     * otherwise try to read a deleted file at the head commit and log a fetch error per
     * deletion, and {@code DiffSummary} would count it as changed rather than removed.
     * Passing the path on both sides instead keeps a file that was merely emptied
     * looking like what it is.
     *
     * @return the diff block, or an empty string when both sides are identical
     */
    public static String unifiedDiff(String oldPath, String newPath,
                                     String oldContent, String newContent) {
        List<String> oldLines = splitLines(oldContent);
        List<String> newLines = splitLines(newContent);
        if (oldLines.equals(newLines)) {
            return "";
        }
        List<Edit> edits = diff(oldLines, newLines);
        List<Hunk> hunks = groupIntoHunks(edits);
        if (hunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        // The diff --git line names the real path on both sides even when one of them
        // does not exist, exactly as git writes it.
        sb.append(header(oldPath != null ? oldPath : newPath,
                newPath != null ? newPath : oldPath));
        sb.append(oldPath != null ? "--- a/" + oldPath : "--- /dev/null").append('\n');
        sb.append(newPath != null ? "+++ b/" + newPath : "+++ /dev/null").append('\n');
        for (Hunk hunk : hunks) {
            renderHunk(sb, hunk);
        }
        return sb.toString();
    }

    /** Git-style placeholder for a binary file. */
    public static String binaryStub(String path) {
        return header(path, path) + "Binary files a/" + path + " and b/" + path + " differ\n";
    }

    /** Placeholder for a file deliberately not diffed, naming why. */
    public static String skippedStub(String path, String reason) {
        return skippedStub(path, path, reason);
    }

    /**
     * Placeholder for a file deliberately not diffed, naming both of its paths — a pure
     * rename has no textual delta to show, so the header is the only place the reviewer
     * can learn where the file went.
     */
    public static String skippedStub(String oldPath, String newPath, String reason) {
        return header(oldPath, newPath) + "Diff omitted: " + reason + "\n";
    }

    private static String header(String oldPath, String newPath) {
        return "diff --git a/" + oldPath + " b/" + newPath + "\n";
    }

    /**
     * Splits into line tokens, without letting a trailing newline produce a phantom empty
     * line. Each token retains its own trailing {@code '\n'}, except possibly the very
     * last token, which has none when {@code content} does not end with a newline.
     * <p>
     * The terminator stays part of the token rather than being stripped before
     * comparison, so a change to only the file's final newline compares as an edit
     * instead of disappearing. {@link #renderHunk} strips it back off before printing and
     * uses its absence to emit the git {@code \ No newline at end of file} marker.
     * Carriage returns are preserved so CRLF files round-trip unchanged.
     */
    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    private enum Op { EQUAL, DELETE, INSERT }

    private record Edit(Op op, String line) { }

    private record Hunk(int oldStart, int oldCount, int newStart, int newCount,
                        List<Edit> edits) { }

    /**
     * Computes the edit script via LCS, building the full {@code (m+1) x (n+1)} table and
     * backtracking over it. That is {@code O(m*n)} in both time and memory, which is why
     * the cell budget below hands anything larger to {@link #coarseDiff}.
     */
    private static List<Edit> diff(List<String> a, List<String> b) {
        int m = a.size();
        int n = b.size();
        // lcs[i][j] is built forward; store full table only when small enough,
        // otherwise fall back to a simple prefix/suffix trim plus block replace.
        long cells = (long) (m + 1) * (n + 1);
        if (cells > 4_000_000L) {
            return coarseDiff(a, b);
        }
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Edit> edits = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < m && j < n) {
            if (a.get(i).equals(b.get(j))) {
                edits.add(new Edit(Op.EQUAL, a.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                edits.add(new Edit(Op.DELETE, a.get(i)));
                i++;
            } else {
                edits.add(new Edit(Op.INSERT, b.get(j)));
                j++;
            }
        }
        while (i < m) {
            edits.add(new Edit(Op.DELETE, a.get(i++)));
        }
        while (j < n) {
            edits.add(new Edit(Op.INSERT, b.get(j++)));
        }
        return edits;
    }

    /**
     * Fallback for large inputs: keeps the common prefix and suffix and replaces the
     * middle wholesale. Produces a valid, if coarse, diff without the quadratic table.
     * <p>
     * Reached once the two sides together exceed the 4M cell budget — around 2000 lines
     * each, not only files near the {@link #MAX_BLOB_BYTES} cap. Two small edits far
     * apart in such a file therefore render as one large replacement rather than two
     * hunks, which is correct but verbose. Replacing the LCS table with Myers' algorithm
     * would remove the trade-off.
     */
    private static List<Edit> coarseDiff(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            edits.add(new Edit(Op.EQUAL, a.get(i)));
        }
        for (int i = prefix; i < a.size() - suffix; i++) {
            edits.add(new Edit(Op.DELETE, a.get(i)));
        }
        for (int j = prefix; j < b.size() - suffix; j++) {
            edits.add(new Edit(Op.INSERT, b.get(j)));
        }
        for (int i = a.size() - suffix; i < a.size(); i++) {
            edits.add(new Edit(Op.EQUAL, a.get(i)));
        }
        return edits;
    }

    /**
     * Groups the edit script into hunks. Changes separated by more than
     * {@code 2 * CONTEXT} unchanged lines become separate hunks; closer ones merge.
     */
    private static List<Hunk> groupIntoHunks(List<Edit> edits) {
        List<Integer> changeIndexes = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            if (edits.get(i).op() != Op.EQUAL) {
                changeIndexes.add(i);
            }
        }
        List<Hunk> hunks = new ArrayList<>();
        if (changeIndexes.isEmpty()) {
            return hunks;
        }
        int groupStart = changeIndexes.get(0);
        int groupEnd = changeIndexes.get(0);
        List<int[]> groups = new ArrayList<>();
        for (int idx = 1; idx < changeIndexes.size(); idx++) {
            int current = changeIndexes.get(idx);
            if (current - groupEnd - 1 > 2 * CONTEXT) {
                groups.add(new int[]{groupStart, groupEnd});
                groupStart = current;
            }
            groupEnd = current;
        }
        groups.add(new int[]{groupStart, groupEnd});

        for (int[] group : groups) {
            int from = Math.max(0, group[0] - CONTEXT);
            int to = Math.min(edits.size() - 1, group[1] + CONTEXT);

            int oldLine = 1;
            int newLine = 1;
            for (int i = 0; i < from; i++) {
                Op op = edits.get(i).op();
                if (op != Op.INSERT) oldLine++;
                if (op != Op.DELETE) newLine++;
            }
            int oldCount = 0;
            int newCount = 0;
            List<Edit> slice = new ArrayList<>();
            for (int i = from; i <= to; i++) {
                Edit e = edits.get(i);
                slice.add(e);
                if (e.op() != Op.INSERT) oldCount++;
                if (e.op() != Op.DELETE) newCount++;
            }
            hunks.add(new Hunk(oldCount == 0 ? oldLine - 1 : oldLine, oldCount,
                    newCount == 0 ? newLine - 1 : newLine, newCount, slice));
        }
        return hunks;
    }

    private static void renderHunk(StringBuilder sb, Hunk hunk) {
        sb.append("@@ -").append(range(hunk.oldStart(), hunk.oldCount()))
                .append(" +").append(range(hunk.newStart(), hunk.newCount()))
                .append(" @@\n");
        for (Edit e : hunk.edits()) {
            String token = e.line();
            boolean missingTrailingNewline = !token.endsWith("\n");
            String text = missingTrailingNewline ? token : token.substring(0, token.length() - 1);
            sb.append(switch (e.op()) {
                case EQUAL -> ' ';
                case DELETE -> '-';
                case INSERT -> '+';
            }).append(text).append('\n');
            if (missingTrailingNewline) {
                sb.append("\\ No newline at end of file\n");
            }
        }
    }

    /** Git omits the count when it is exactly 1. */
    private static String range(int start, int count) {
        return count == 1 ? String.valueOf(start) : start + "," + count;
    }
}
