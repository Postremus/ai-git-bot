package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One temporary git checkout, created by {@link WorkspaceService} and owned by whoever
 * opened it: use it in try-with-resources, {@link #close()} deletes it.
 * <p>
 * Operations throw {@link WorkspaceException} when git fails, and after {@link #close()}.
 * Remote operations authenticate per command: the credential material exists on disk only
 * while that command runs. If it cannot be removed afterwards, the workspace is discarded
 * rather than leave a token on disk.
 */
@Slf4j
public class Workspace implements AutoCloseable {

    private final WorkspaceService service;
    private final WorkspaceSetup setup;

    Workspace(WorkspaceService service, WorkspaceSetup setup) {
        this.service = service;
        this.setup = setup;
    }

    /** The checkout directory. */
    public Path dir() {
        return setup.workspaceDir();
    }

    /**
     * Whether the checkout contains changes git would commit. Empty directories are
     * ignored by git and therefore do not count.
     */
    public boolean hasUncommittedChanges() {
        return !status().isBlank();
    }

    /**
     * The checkout-relative paths (forward slashes) of every file git sees as added,
     * modified, renamed (destination path) or untracked.
     */
    public List<String> listChangedFiles() {
        List<String> changed = new ArrayList<>();
        for (String line : status().split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            // Porcelain v1 format: "XY <path>" or "XY <old> -> <new>".
            String entry = line.length() > 3 ? line.substring(3).trim() : line.trim();
            int arrow = entry.indexOf(" -> ");
            if (arrow >= 0) {
                entry = entry.substring(arrow + 4).trim();
            }
            // Drop surrounding quotes Git adds for paths with special chars.
            if (entry.length() >= 2 && entry.startsWith("\"") && entry.endsWith("\"")) {
                entry = entry.substring(1, entry.length() - 1);
            }
            if (!entry.isBlank()) {
                changed.add(entry.replace('\\', '/'));
            }
        }
        return changed;
    }

    /** A {@code git diff --stat HEAD} summary of the uncommitted changes, possibly empty. */
    public String diffStat() {
        CommandResult result = git(15, "diff", "--stat", "HEAD");
        if (!result.success()) {
            throw new WorkspaceException("git diff --stat failed: " + result.output());
        }
        return result.output() == null ? "" : result.output().strip();
    }

    /**
     * Commits all changes and pushes them to {@code branch} on the remote.
     *
     * @param createNewBranch {@code true} to create {@code branch} first; otherwise the
     *                        checkout must already be on it
     */
    public void commitAndPush(String branch, String message,
                              String authorName, String authorEmail, boolean createNewBranch) {
        synchronized (setup) {
            if (!git(10, "config", "user.email", authorEmail).success()) {
                log.warn("Could not set git user.email, continuing anyway");
            }
            if (!git(10, "config", "user.name", authorName).success()) {
                log.warn("Could not set git user.name, continuing anyway");
            }
            if (createNewBranch) {
                require(git(15, "checkout", "-b", branch), "git checkout -b " + branch);
            }
            require(git(15, "add", "-A"), "git add -A");
            CommandResult commit = git(15, "commit", "-m", message);
            if (!commit.success()) {
                if (commit.output().contains("nothing to commit")) {
                    throw new WorkspaceException("Nothing to commit: the workspace has no file changes");
                }
                throw new WorkspaceException("git commit failed: " + commit.output());
            }
            require(remoteGit(60, "push", "origin", branch), "git push");
            log.info("Successfully committed and pushed to branch '{}'", branch);
        }
    }

    /** Fetches {@code branch} into {@code refs/remotes/origin/<branch>}. */
    public void fetchBranch(String branch) {
        require(remoteGit(60, "fetch", "origin", "refs/heads/" + branch + ":refs/remotes/origin/" + branch),
                "git fetch");
    }

    /** Deletes the workspace. Idempotent; never throws. */
    @Override
    public void close() {
        service.cleanupWorkspace(setup);
    }

    /** Runs a local git command in the checkout. */
    CommandResult git(int timeoutSeconds, String... args) {
        ensureOpen();
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return service.runCommand(dir().toFile(), command, timeoutSeconds);
    }

    /** Runs an authenticated git command in the checkout; see {@link WorkspaceService} for the discard rule. */
    CommandResult remoteGit(int timeoutSeconds, String... args) {
        ensureOpen();
        CommandResult result = service.runRemoteCommand(setup, dir().toFile(), timeoutSeconds, args);
        if (setup.closed()) {
            // runRemoteCommand discarded the workspace because the auth files could not be removed.
            throw new WorkspaceException("Could not remove Git authentication files; workspace discarded");
        }
        return result;
    }

    private String status() {
        CommandResult result = git(10, "status", "--porcelain");
        if (!result.success() || result.output() == null) {
            throw new WorkspaceException("git status failed: " + result.output());
        }
        return result.output();
    }

    private static void require(CommandResult result, String step) {
        if (!result.success()) {
            throw new WorkspaceException(step + " failed: " + result.output());
        }
    }

    private void ensureOpen() {
        if (setup.closed()) {
            throw new WorkspaceException("Workspace was already closed");
        }
    }
}
