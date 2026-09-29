package org.remus.giteabot.agent.validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.PullRequestHead;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {
    private WorkspaceService workspaceService = new WorkspaceService();
    @Mock
    private RepositoryApiClient repositoryClient;
    @TempDir
    Path tempDir;
    @Test
    void openWorkspace_resolvesCheckoutOnceAcrossFallback() throws Exception {
        Path remote = createBareRepository("once", "content");
        runGit(remote, "update-ref", "refs/pull/42/head", "refs/heads/main");
        when(repositoryClient.getRepositoryRemote("owner", "repo")).thenReturn(remote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remote.toString(), "dummy-token"));

        workspaceService.openWorkspace(repositoryClient, "owner", "repo", "missing-branch", 42L).close();

        verify(repositoryClient, times(1)).getRepositoryRemote("owner", "repo");
        verify(repositoryClient, times(1)).getCredentials();
    }

    private Path createBareRepository(String name, String content) throws IOException, InterruptedException {
        Path bare = tempDir.resolve(name + "-remote");
        Files.createDirectories(bare);
        runGit(bare, "init", "--bare");
        Path source = tempDir.resolve(name + "-source");
        Files.createDirectories(source);
        runGit(source, "init");
        runGit(source, "config", "user.email", "test@example.com");
        runGit(source, "config", "user.name", "Test User");
        runGit(source, "branch", "-M", "main");
        Files.writeString(source.resolve("README.md"), content);
        runGit(source, "add", "README.md");
        runGit(source, "commit", "-m", "initial");
        runGit(source, "remote", "add", "origin", bare.toString());
        runGit(source, "push", "origin", "main");
        return bare;
    }

    private String runGitCapture(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        return output.trim();
    }

    private void runGit(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        assertThat(exitCode).isZero();
    }

    @Test
    void openWorkspace_clonesBranch() throws Exception {
        Path remote = createBareRepository("open", "open content");
        when(repositoryClient.getRepositoryRemote("owner", "repo")).thenReturn(remote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remote.toString(), "dummy-token"));

        try (Workspace workspace = workspaceService.openWorkspace(repositoryClient, "owner", "repo", "main", null)) {
            assertThat(Files.readString(workspace.dir().resolve("README.md"))).isEqualTo("open content");
        }
    }

    @Test
    void openWorkspace_fallsBackToPrHeadRefAndKeepsOneDirectory() throws Exception {
        Path base = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(base.toString());
        Path remote = createBareRepository("fallback", "pr content");
        runGit(remote, "update-ref", "refs/pull/42/head", "refs/heads/main");
        when(repositoryClient.getRepositoryRemote("owner", "repo")).thenReturn(remote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remote.toString(), "dummy-token"));

        Workspace workspace = workspaceService.openWorkspace(repositoryClient, "owner", "repo", "missing-branch", 42L);
        try {
            assertThat(runGitCapture(workspace.dir(), "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("missing-branch");
            try (var children = Files.list(base)) {
                assertThat(children.filter(p -> p.getFileName().toString().startsWith("agent-workspace-"))).hasSize(1);
            }
        } finally {
            workspace.close();
        }
        try (var children = Files.list(base)) {
            assertThat(children).isEmpty();
        }
    }

    @Test
    void openWorkspace_prRefFetchFailureLeavesNoDirectory() throws Exception {
        Path base = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(base.toString());
        Path remote = createBareRepository("no-pr-ref", "content");
        when(repositoryClient.getRepositoryRemote("owner", "repo")).thenReturn(remote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remote.toString(), "dummy-token"));

        assertThatThrownBy(() -> workspaceService.openWorkspace(repositoryClient, "owner", "repo", "missing-branch", 42L))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageStartingWith("Failed to fetch PR head ref for PR #42:");
        try (var children = Files.list(base)) {
            assertThat(children).isEmpty();
        }
    }

    @Test
    void openWorkspace_cloneFailureWithoutPrThrowsAndLeavesNoDirectory() throws Exception {
        Path base = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(base.toString());
        Path remote = createBareRepository("no-branch", "content");
        when(repositoryClient.getRepositoryRemote("owner", "repo")).thenReturn(remote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remote.toString(), "dummy-token"));

        assertThatThrownBy(() -> workspaceService.openWorkspace(repositoryClient, "owner", "repo", "missing-branch", null))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageStartingWith("Failed to clone repository:");
        try (var children = Files.list(base)) {
            assertThat(children).isEmpty();
        }
    }

    @Test
    void openWorkspace_resolutionFailureThrowsWithoutAllocatingWorkspace() {
        Path base = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(base.toString());
        when(repositoryClient.getRepositoryRemote("owner", "repo"))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> workspaceService.openWorkspace(repositoryClient, "owner", "repo", "main", null))
                .isInstanceOf(WorkspaceException.class)
                .hasMessage("Failed to resolve repository checkout: provider unavailable");
        assertThat(base).doesNotExist();
    }

    @Test
    void openWorkspace_rejectsIncompleteCheckoutConfiguration() {
        Path base = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(base.toString());
        when(repositoryClient.getRepositoryRemote("owner", "repo")).thenReturn(" ");
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", "https://git.example.com", "token"));

        assertThatThrownBy(() -> workspaceService.openWorkspace(repositoryClient, "owner", "repo", "main", null))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("incomplete checkout configuration");
        assertThat(base).doesNotExist();
    }

    @Test
    void openWritablePullRequestWorkspace_pushesForkMainWithoutChangingTargetMain() throws Exception {
        Path targetRemote = createBareRepository("target", "target content");
        Path forkRemote = createBareRepository("fork", "fork content");
        String targetBefore = runGitCapture(targetRemote, "rev-parse", "refs/heads/main");
        String forkBefore = runGitCapture(forkRemote, "rev-parse", "refs/heads/main");
        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 7L, "main"))
                .thenReturn(new PullRequestHead("contributor", "project", "main", forkBefore));
        when(repositoryClient.getRepositoryRemote("contributor", "project")).thenReturn(forkRemote.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", forkRemote.toString(), "dummy-token"));

        try (Workspace workspace = workspaceService.openWritablePullRequestWorkspace(
                repositoryClient, "base", "project", "main", 7L)) {
            Files.writeString(workspace.dir().resolve("README.md"), "bot update");
            workspace.commitAndPush("main", "docs: update", "AI Agent", "ai-agent@bot.local", false);
        }

        assertThat(runGitCapture(targetRemote, "rev-parse", "refs/heads/main")).isEqualTo(targetBefore);
        assertThat(runGitCapture(forkRemote, "rev-parse", "refs/heads/main")).isNotEqualTo(forkBefore);
        verify(repositoryClient, never()).getRepositoryRemote("base", "project");
    }

    @Test
    void openWritablePullRequestWorkspace_authoritativeFailureNeverFallsBackToTarget() {
        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 7L, "main"))
                .thenThrow(new IllegalStateException("missing head repository"));

        assertThatThrownBy(() -> workspaceService.openWritablePullRequestWorkspace(
                repositoryClient, "base", "project", "main", 7L))
                .isInstanceOf(WorkspaceException.class)
                .hasMessage("Failed to resolve writable pull-request head: missing head repository");
        verify(repositoryClient, never()).getRepositoryRemote("base", "project");
        verify(repositoryClient, never()).getCredentials();
    }

    @Test
    void openWritablePullRequestWorkspace_nonAuthoritativeProviderKeepsPrRefFallback() throws Exception {
        Path remoteDir = createBareRepository("provider-target", "pr content");
        runGit(remoteDir, "update-ref", "refs/pull/42/head", "refs/heads/main");
        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(false);
        when(repositoryClient.getRepositoryRemote("base", "project")).thenReturn(remoteDir.toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remoteDir.toString(), "dummy-token"));

        try (Workspace workspace = workspaceService.openWritablePullRequestWorkspace(
                repositoryClient, "base", "project", "missing-branch", 42L)) {
            assertThat(runGitCapture(workspace.dir(), "rev-parse", "--abbrev-ref", "HEAD"))
                    .isEqualTo("missing-branch");
        }
    }

    @Test
    void openWorkspace_sendsBasicHeaderWhenProviderOptsIn() throws IOException {
        java.util.List<String> authorizationHeaders = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            authorizationHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String remote = "http://127.0.0.1:" + server.getAddress().getPort() + "/org/project/_git/repo";
            when(repositoryClient.getRepositoryRemote("any", "any")).thenReturn(remote);
            when(repositoryClient.getCredentials()).thenReturn(RepositoryCredentials.of("", remote, "pat"));
            when(repositoryClient.usesGitAuthorizationHeader()).thenReturn(true);

            assertThatThrownBy(() -> workspaceService.openWorkspace(repositoryClient, "any", "any", "main", null))
                    .isInstanceOf(WorkspaceException.class);
            assertThat(authorizationHeaders).isNotEmpty().allMatch(header -> header.equals(
                    "Basic " + java.util.Base64.getEncoder().encodeToString(":pat".getBytes())));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void openEmptyWorkspace_initialisesRepositoryThatCanFetchFromRemote() throws Exception {
        Path remote = createBareRepository("empty", "content");
        runGit(remote, "config", "uploadpack.allowAnySHA1InWant", "true");
        String head = runGitCapture(remote, "rev-parse", "refs/heads/main");
        when(repositoryClient.getRepositoryRemote("owner", "repo")).thenReturn(remote.toUri().toString());
        when(repositoryClient.getCredentials())
                .thenReturn(RepositoryCredentials.of("", remote.toUri().toString(), "dummy-token"));

        try (Workspace workspace = workspaceService.openEmptyWorkspace(repositoryClient, "owner", "repo")) {
            assertThat(workspace.git(10, "rev-parse", "--git-dir").success()).isTrue();
            CommandResult fetch = workspace.remoteGit(60, "fetch", "--depth=1", "--end-of-options",
                    remote.toUri().toString(), head);
            assertThat(fetch.success()).as(fetch.output()).isTrue();
        }
    }
}
