package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the hand-rolled unified-diff generator. Expected outputs are
 * byte-for-byte what `git diff --no-color -U3` produces for the same inputs.
 */
class AzureDevopsDiffBuilderTest {

    /**
     * Renders a file that kept its path. Production always knows both paths — a rename
     * names them separately — so the builder exposes only the two-path form; most cases
     * here are about hunk shape rather than paths and read better without the repetition.
     */
    private static String samePath(String path, String oldContent, String newContent) {
        return AzureDevopsDiffBuilder.unifiedDiff(path, path, oldContent, newContent);
    }

    @Test
    void unifiedDiff_singleLineChange() {
        String oldContent = "line1\nline2\nline3\n";
        String newContent = "line1\nCHANGED\nline3\n";

        String diff = samePath("src/Foo.java", oldContent, newContent);

        assertEquals("""
                diff --git a/src/Foo.java b/src/Foo.java
                --- a/src/Foo.java
                +++ b/src/Foo.java
                @@ -1,3 +1,3 @@
                 line1
                -line2
                +CHANGED
                 line3
                """, diff);
    }

    @Test
    void unifiedDiff_identicalContentReturnsEmpty() {
        assertEquals("", samePath("a.txt", "same\n", "same\n"));
    }

    @Test
    void unifiedDiff_fileFilledFromEmpty_keepsBothPathsWhenBothAreGiven() {
        // Both paths given means both sides exist: a file that was empty and now is not.
        String diff = samePath("new.txt", "", "hello\nworld\n");

        assertEquals("""
                diff --git a/new.txt b/new.txt
                --- a/new.txt
                +++ b/new.txt
                @@ -0,0 +1,2 @@
                +hello
                +world
                """, diff);
    }

    @Test
    void unifiedDiff_addedFile_namesTheAbsentOldSideDevNull() {
        String diff = AzureDevopsDiffBuilder.unifiedDiff(null, "new.txt", "", "hello\n");

        assertEquals("""
                diff --git a/new.txt b/new.txt
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1 @@
                +hello
                """, diff);
    }

    @Test
    void unifiedDiff_deletedFile_namesTheAbsentNewSideDevNull() {
        String diff = AzureDevopsDiffBuilder.unifiedDiff("gone.txt", null, "bye\n", "");

        assertEquals("""
                diff --git a/gone.txt b/gone.txt
                --- a/gone.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -bye
                """, diff);
    }

    @Test
    void unifiedDiff_fileEmptied_keepsBothPathsWhenBothAreGiven() {
        // The counterpart of the case above: the file still exists, it just has no
        // content left, so neither side is /dev/null.
        String diff = samePath("gone.txt", "bye\n", "");

        assertEquals("""
                diff --git a/gone.txt b/gone.txt
                --- a/gone.txt
                +++ b/gone.txt
                @@ -1 +0,0 @@
                -bye
                """, diff);
    }

    @Test
    void unifiedDiff_omitsCountWhenOne() {
        // git writes "@@ -1 +1 @@", not "@@ -1,1 +1,1 @@"
        String diff = samePath("one.txt", "a\n", "b\n");

        assertTrue(diff.contains("@@ -1 +1 @@"), "was:\n" + diff);
    }

    @Test
    void unifiedDiff_separateHunksWhenChangesAreFarApart() {
        StringBuilder oldSb = new StringBuilder();
        StringBuilder newSb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            oldSb.append("line").append(i).append("\n");
            newSb.append(i == 2 ? "FIRST\n" : i == 25 ? "SECOND\n" : "line" + i + "\n");
        }

        String diff = samePath("far.txt", oldSb.toString(), newSb.toString());

        long hunkCount = diff.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(2, hunkCount, "changes 23 lines apart must not merge into one hunk:\n" + diff);
    }

    @Test
    void unifiedDiff_mergesHunksWhenChangesAreClose() {
        StringBuilder oldSb = new StringBuilder();
        StringBuilder newSb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            oldSb.append("line").append(i).append("\n");
            newSb.append(i == 5 ? "FIRST\n" : i == 8 ? "SECOND\n" : "line" + i + "\n");
        }

        String diff = samePath("near.txt", oldSb.toString(), newSb.toString());

        long hunkCount = diff.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(1, hunkCount, "changes 3 lines apart must merge into one hunk:\n" + diff);
    }

    @Test
    void unifiedDiff_mergesHunksAtExactlyTwiceContextGap() {
        // Verified against real git: two single-line substitutions with exactly
        // 2*CONTEXT (6) unchanged lines between them merge into one hunk.
        StringBuilder oldSb = new StringBuilder();
        StringBuilder newSb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            oldSb.append("line").append(i).append("\n");
            newSb.append(i == 5 ? "FIRST\n" : i == 12 ? "SECOND\n" : "line" + i + "\n");
        }

        String diff = samePath("boundary6.txt", oldSb.toString(), newSb.toString());

        long hunkCount = diff.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(1, hunkCount, "exactly 6 unchanged lines between changes must merge:\n" + diff);
    }

    @Test
    void unifiedDiff_splitsHunksAtTwiceContextGapPlusOne() {
        // Companion boundary case: one more unchanged line (7) than the merge case
        // above must split into two hunks. Verified against real git.
        StringBuilder oldSb = new StringBuilder();
        StringBuilder newSb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            oldSb.append("line").append(i).append("\n");
            newSb.append(i == 5 ? "FIRST\n" : i == 13 ? "SECOND\n" : "line" + i + "\n");
        }

        String diff = samePath("boundary7.txt", oldSb.toString(), newSb.toString());

        long hunkCount = diff.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(2, hunkCount, "exactly 7 unchanged lines between changes must split:\n" + diff);
    }

    @Test
    void unifiedDiff_fileWithoutTrailingNewline() {
        String diff = samePath("nl.txt", "a\nb", "a\nc");

        // Verified against real `git diff --no-color -U3 --no-index`: both the deleted
        // and the inserted line lack a trailing newline, so each gets its own marker.
        assertEquals("""
                diff --git a/nl.txt b/nl.txt
                --- a/nl.txt
                +++ b/nl.txt
                @@ -1,2 +1,2 @@
                 a
                -b
                \\ No newline at end of file
                +c
                \\ No newline at end of file
                """, diff);
    }

    @Test
    void unifiedDiff_trailingNewlineOnlyChange_removed() {
        // Verified against real git: content is otherwise identical, only the final
        // newline is dropped. Must not vanish into an empty diff.
        String diff = samePath("f.txt", "a\nb\n", "a\nb");

        assertEquals("""
                diff --git a/f.txt b/f.txt
                --- a/f.txt
                +++ b/f.txt
                @@ -1,2 +1,2 @@
                 a
                -b
                +b
                \\ No newline at end of file
                """, diff);
    }

    @Test
    void unifiedDiff_trailingNewlineOnlyChange_added() {
        // Mirror of the removal case: the new content adds back the trailing newline.
        String diff = samePath("f.txt", "a\nb", "a\nb\n");

        assertEquals("""
                diff --git a/f.txt b/f.txt
                --- a/f.txt
                +++ b/f.txt
                @@ -1,2 +1,2 @@
                 a
                -b
                \\ No newline at end of file
                +b
                """, diff);
    }

    @Test
    void unifiedDiff_handlesCrlfWithoutDuplicatingCarriageReturns() {
        String diff = samePath("crlf.txt", "a\r\nb\r\n", "a\r\nc\r\n");

        assertFalse(diff.contains("\r\r"), "must not double carriage returns:\n" + diff);
        assertTrue(diff.contains("@@"), "was:\n" + diff);
    }

    @Test
    void binaryStub_rendersGitStyleMessage() {
        assertEquals("""
                diff --git a/img.png b/img.png
                Binary files a/img.png and b/img.png differ
                """, AzureDevopsDiffBuilder.binaryStub("img.png"));
    }

    @Test
    void skippedStub_namesTheReason() {
        String stub = AzureDevopsDiffBuilder.skippedStub("big.bin", "file exceeds 512 KiB");

        assertTrue(stub.startsWith("diff --git a/big.bin b/big.bin\n"), "was:\n" + stub);
        assertTrue(stub.contains("file exceeds 512 KiB"), "was:\n" + stub);
        assertTrue(stub.endsWith("\n"));
    }

    @Test
    void unifiedDiff_coarseDiffPath_producesValidUnifiedDiffAboveCellBudget() {
        // Drives the input past the 4M-cell budget (documented in the class Javadoc) so
        // diff() takes the coarseDiff() fallback, which is the *only* path exercised for
        // near-MAX_BLOB_BYTES files per the corrected design spec, not a rare fallback.
        // 2002 * 2002 = 4,008,004 > 4,000,000.
        int lines = 2001;
        StringBuilder oldSb = new StringBuilder();
        StringBuilder newSb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            if (i == 1000) {
                oldSb.append("OLD\n");
                newSb.append("NEW\n");
            } else {
                oldSb.append("line").append(i).append('\n');
                newSb.append("line").append(i).append('\n');
            }
        }

        String diff = samePath("big.txt", oldSb.toString(), newSb.toString());

        assertTrue(diff.startsWith("diff --git a/big.txt b/big.txt\n--- a/big.txt\n+++ b/big.txt\n"),
                "was:\n" + diff.substring(0, Math.min(200, diff.length())));
        java.util.regex.Matcher header = java.util.regex.Pattern
                .compile("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@")
                .matcher(diff);
        assertTrue(header.find(), "expected a valid unified-diff hunk header, was:\n" + diff);
        assertEquals(header.group(1), header.group(3),
                "old and new start lines must match: identical prefix precedes the change");
        assertTrue(diff.contains("-OLD\n"), "was:\n" + diff);
        assertTrue(diff.contains("+NEW\n"), "was:\n" + diff);
        assertFalse(header.find(), "coarseDiff must produce exactly one hunk for one localized change");
    }

    @Test
    void unifiedDiff_coarseDiffPath_stillDetectsTrailingNewlineOnlyChange() {
        // Companion to the "trailing-newline-only change" tests above, but through the
        // coarseDiff path: a change limited to the file's final newline must not
        // disappear even when the file is large enough to bypass the full LCS table.
        int lines = 2005;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines - 1; i++) {
            sb.append("line").append(i).append('\n');
        }
        String oldContent = sb + "last\n";
        String newContent = sb + "last";

        String diff = samePath("big2.txt", oldContent, newContent);

        assertTrue(diff.contains("@@"), "trailing-newline-only change must not vanish:\n"
                + diff.substring(Math.max(0, diff.length() - 300)));
        assertTrue(diff.contains("-last\n"), "was tail:\n" + diff.substring(Math.max(0, diff.length() - 300)));
        assertTrue(diff.contains("+last\n\\ No newline at end of file\n"),
                "was tail:\n" + diff.substring(Math.max(0, diff.length() - 300)));
    }

    @Test
    void constants_matchSpec() {
        assertEquals(300, AzureDevopsDiffBuilder.MAX_FILES);
        assertEquals(512 * 1024, AzureDevopsDiffBuilder.MAX_BLOB_BYTES);
        assertEquals(3, AzureDevopsDiffBuilder.CONTEXT);
    }
}
