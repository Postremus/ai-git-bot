package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.remus.giteabot.repository.model.RepositoryCredentials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceTest {

    @TempDir
    Path tempDir;

    private WorkspaceDirectories directories;
    private Path remote;
    private Workspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        directories = new WorkspaceDirectories(tempDir.resolve("workspaces"));
        remote = createBareRepository();
        workspace = new Workspace(directories.createRoot(), new RepositoryCheckout(remote.toString(),
                RepositoryCredentials.of("", remote.toString(), "dummy-token"), false), directories);
        CommandResult clone = workspace.remoteGitAt(workspace.root(), 60,
                "clone", remote.toString(), WorkspaceDirectories.REPOSITORY_DIRECTORY_NAME);
        assertThat(clone.success()).as(clone.output()).isTrue();
    }

    @Test
    void hasUncommittedChanges_detectsModifiedTrackedFile() throws IOException {
        assertThat(workspace.hasUncommittedChanges()).isFalse();
        Files.writeString(workspace.dir().resolve("README.md"), "changed");

        assertThat(workspace.hasUncommittedChanges()).isTrue();
    }

    @Test
    void hasUncommittedChanges_ignoresEmptyDirectory() throws IOException {
        Files.createDirectories(workspace.dir().resolve("empty-dir"));

        assertThat(workspace.hasUncommittedChanges()).isFalse();
    }

    @Test
    void listChangedFiles_reportsAddedModifiedAndRenamedPaths() throws Exception {
        Files.writeString(workspace.dir().resolve("README.md"), "changed");
        Files.writeString(workspace.dir().resolve("NEW.md"), "new");
        assertThat(workspace.git(15, "mv", "OTHER.md", "RENAMED.md").success()).isTrue();

        assertThat(workspace.listChangedFiles())
                .containsExactlyInAnyOrder("README.md", "NEW.md", "RENAMED.md");
    }

    @Test
    void readOperations_throwWhenGitStatusFails() throws IOException {
        // A corrupt HEAD makes every git status fail.
        Files.writeString(workspace.dir().resolve(".git/HEAD"), "garbage\n");

        assertThatThrownBy(workspace::listChangedFiles)
                .isInstanceOf(WorkspaceException.class).hasMessageStartingWith("git status failed");
        assertThatThrownBy(workspace::hasUncommittedChanges)
                .isInstanceOf(WorkspaceException.class).hasMessageStartingWith("git status failed");
        assertThatThrownBy(workspace::diffStat)
                .isInstanceOf(WorkspaceException.class).hasMessageStartingWith("git diff --stat failed");
    }

    @Test
    void commitAndPush_pushesNewBranch() throws Exception {
        Files.writeString(workspace.dir().resolve("README.md"), "changed");

        workspace.commitAndPush("feature", "test commit", "Test User", "test@example.com", true);

        assertThat(gitOutput(remote, "log", "-1", "--format=%s", "refs/heads/feature")).isEqualTo("test commit");
    }

    @Test
    void commitAndPush_throwsWithGitOutputWhenPushIsRejected() throws Exception {
        // Advance the remote so the workspace's push is not a fast-forward.
        Path other = tempDir.resolve("other");
        git(tempDir, "clone", "-q", remote.toString(), other.toString());
        Files.writeString(other.resolve("README.md"), "upstream change");
        git(other, "-c", "user.email=t@e.com", "-c", "user.name=T", "commit", "-q", "-am", "upstream");
        git(other, "push", "-q", "origin", "main");
        Files.writeString(workspace.dir().resolve("README.md"), "local change");

        assertThatThrownBy(() -> workspace.commitAndPush("main", "local", "Test User", "test@example.com", false))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageStartingWith("git push failed:")
                .hasMessageContaining("rejected");
    }

    @Test
    void commitAndPush_throwsWhenNothingToCommit() {
        assertThatThrownBy(() -> workspace.commitAndPush("main", "empty", "Test User", "test@example.com", false))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("Nothing to commit");
    }

    @Test
    void fetchBranch_fetchesIntoRemoteTrackingRef() throws Exception {
        git(remote, "branch", "topic", "main");

        workspace.fetchBranch("topic");

        assertThat(workspace.git(10, "rev-parse", "--verify", "refs/remotes/origin/topic").success()).isTrue();
    }

    @Test
    void fetchBranch_throwsForUnknownBranch() {
        assertThatThrownBy(() -> workspace.fetchBranch("does-not-exist"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageStartingWith("git fetch failed:");
    }

    @Test
    void closeDeletesRootAndIsIdempotent() {
        Path root = workspace.root();

        workspace.close();
        workspace.close();

        assertThat(root).doesNotExist();
    }

    @Test
    void operationsAfterCloseThrow() {
        workspace.close();

        assertThatThrownBy(workspace::hasUncommittedChanges).isInstanceOf(WorkspaceException.class)
                .hasMessage("Workspace was already closed");
        assertThatThrownBy(() -> workspace.git(10, "status")).isInstanceOf(WorkspaceException.class);
        assertThatThrownBy(() -> workspace.remoteGit(10, "fetch")).isInstanceOf(WorkspaceException.class);
        assertThatThrownBy(() -> workspace.commitAndPush("main", "m", "n", "e", false))
                .isInstanceOf(WorkspaceException.class);
    }

    @Test
    void remoteGit_discardsWorkspaceWhenAuthCleanupFails() throws Exception {
        Workspace failing = new Workspace(directories.createRoot(), new RepositoryCheckout(remote.toString(),
                RepositoryCredentials.of("", remote.toString(), "dummy-token"), false), directories) {
            @Override
            GitAuthentication newAuthentication() {
                return new GitAuthentication(checkout(), root()) {
                    @Override
                    boolean clear() {
                        super.clear();
                        return false;
                    }
                };
            }
        };
        Path root = failing.root();

        assertThatThrownBy(() -> failing.remoteGitAt(root, 60, "clone", remote.toString(), "repository"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessage("Could not remove Git authentication files; workspace discarded");
        assertThat(root).doesNotExist();
        assertThatThrownBy(() -> failing.git(10, "status"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessage("Workspace was already closed");
    }

    @Test
    void remoteGit_clearsAuthMaterialWhenCommandThrowsError() throws Exception {
        RepositoryCheckout httpCheckout = new RepositoryCheckout("http://127.0.0.1:9/owner/repo.git",
                RepositoryCredentials.of("", "http://127.0.0.1:9", "token"), false);
        Path[] capturedCredentialsFile = new Path[1];
        Workspace erroring = new Workspace(directories.createRoot(), new RepositoryCheckout(remote.toString(),
                RepositoryCredentials.of("", remote.toString(), "dummy-token"), false), directories) {
            @Override
            GitAuthentication newAuthentication() {
                return new GitAuthentication(httpCheckout, root()) {
                    @Override
                    void write() throws IOException {
                        super.write();
                        capturedCredentialsFile[0] = credentialsFile();
                        throw new AssertionError("boom");
                    }
                };
            }
        };
        CommandResult clone = erroring.gitAt(erroring.root(), 60, "clone", remote.toString(),
                WorkspaceDirectories.REPOSITORY_DIRECTORY_NAME);
        assertThat(clone.success()).as(clone.output()).isTrue();

        assertThatThrownBy(() -> erroring.remoteGitAt(erroring.dir(), 10, "ls-remote",
                "http://127.0.0.1:9/owner/repo.git"))
                .isInstanceOf(AssertionError.class)
                .hasMessage("boom");

        assertThat(capturedCredentialsFile[0]).isNotNull();
        assertThat(capturedCredentialsFile[0]).doesNotExist();
        assertThat(erroring.git(10, "status").success()).isTrue();
    }

    @Test
    void remoteGit_leavesOnlyMarkerAndCheckoutInRootAfterHttpCommand() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        Workspace httpWorkspace = null;
        try {
            String remoteUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/owner/repo.git";
            httpWorkspace = new Workspace(directories.createRoot(), new RepositoryCheckout(remoteUrl,
                    RepositoryCredentials.of("", "http://127.0.0.1:" + server.getAddress().getPort(), "token"),
                    false), directories);
            Path root = httpWorkspace.root();

            httpWorkspace.remoteGitAt(root, 30, "ls-remote", remoteUrl);

            try (var children = Files.list(root)) {
                assertThat(children.map(p -> p.getFileName().toString()))
                        .containsExactly(".agent-workspace");
            }
        } finally {
            if (httpWorkspace != null) {
                httpWorkspace.close();
            }
            server.stop(0);
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private Path createBareRepository() throws Exception {
        Path bare = tempDir.resolve("remote.git");
        git(tempDir, "init", "-q", "--bare", "-b", "main", bare.toString());
        Path source = tempDir.resolve("source");
        git(tempDir, "init", "-q", "-b", "main", source.toString());
        Files.writeString(source.resolve("README.md"), "initial");
        Files.writeString(source.resolve("OTHER.md"), "other");
        git(source, "add", ".");
        git(source, "-c", "user.email=t@e.com", "-c", "user.name=T", "commit", "-q", "-m", "initial");
        git(source, "push", "-q", bare.toString(), "main");
        return bare;
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        run(dir, args);
    }

    private static String gitOutput(Path dir, String... args) throws IOException, InterruptedException {
        return run(dir, args).trim();
    }

    private static String run(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }
}
