package org.remus.giteabot.agent.validation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.PullRequestHead;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {
    @InjectMocks
    private WorkspaceService workspaceService;
    @Mock
    private RepositoryApiClient repositoryClient;
    @TempDir
    Path tempDir;

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

    @Test
    void openWritablePullRequestWorkspace_pushesForkMainWithoutChangingTargetMain() throws Exception {
        Path targetRemote = createBareRepository("target", "target content");
        Path forkRemote = createBareRepository("fork", "fork content");
        String targetBefore = runGitCapture(targetRemote, "rev-parse", "refs/heads/main");
        String forkBefore = runGitCapture(forkRemote, "rev-parse", "refs/heads/main");

        when(repositoryClient.requiresAuthoritativePullRequestHead()).thenReturn(true);
        when(repositoryClient.getPullRequestHead("base", "project", 7L, "main"))
                .thenReturn(new PullRequestHead("contributor", "project", "main", forkBefore));
        when(repositoryClient.getRepositoryRemote("contributor", "project"))
                .thenReturn(forkRemote.toString());
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
    void cleanupWorkspace_setupDeletesCredentialFileAndRootTogether() throws IOException {
        // The holder keeps the credential-file reference even when the file was
        // never registered for the workspace — exactly the
        // situation of the first attempt in the branch-clone fallback. Cleanup
        // must remove the file and the private parent together.
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token", setup.workspaceDir());
        assertThat(credentials).isNotNull();

        workspaceService.cleanupWorkspace(setup);

        assertThat(credentials).doesNotExist();
        assertThat(setup.workspaceRoot()).doesNotExist();
    }

    @Test
    void cleanupWorkspace_setupNull_doesNotThrow() {
        workspaceService.cleanupWorkspace((WorkspaceSetup) null);
        // no exception expected
    }

    @Test
    void failedCleanupIsRetriedBeforeCreatingAnotherWorkspace() throws IOException {
        FailOnceCleanupWorkspaceService service = new FailOnceCleanupWorkspaceService();
        WorkspaceSetup failed = service.createWorkspaceSetup();

        assertThat(service.cleanupWorkspace(failed)).isFalse();
        assertThat(failed.workspaceRoot()).exists();

        WorkspaceSetup next = service.createWorkspaceSetup();
        assertThat(failed.workspaceRoot()).doesNotExist();
        service.cleanupWorkspace(next);
    }

    @Test
    void gitCommand_sshUsesIntegrationKeyAndPinnedHostKeys() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        RepositoryCredentials credentials = RepositoryCredentials
                .of("https://gitea.example.com", "git@gitea.example.com:owner/repo.git", "token")
                .withSsh("private key", "gitea.example.com ssh-ed25519 host-key");
        workspaceService.createAuthenticationFiles(credentials.cloneUrl(), credentials, setup);

        Path privateKey = setup.sshPrivateKeyFile();
        Path knownHosts = setup.sshKnownHostsFile();
        assertThat(Files.readString(privateKey)).isEqualTo("private key\n");
        assertThat(Files.readString(knownHosts)).isEqualTo("gitea.example.com ssh-ed25519 host-key\n");
        assertThat(workspaceService.withGitConfig(
                workspaceService.gitConfigArgs(setup), "clone", credentials.cloneUrl()))
                .containsExactly(
                        "git", "-c",
                        "core.sshCommand=ssh -F /dev/null -i '" + privateKey.toAbsolutePath() + "'"
                                + " -o UserKnownHostsFile='" + knownHosts.toAbsolutePath() + "'"
                                + " -o GlobalKnownHostsFile=/dev/null -o IdentitiesOnly=yes"
                                + " -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=yes",
                         "clone", credentials.cloneUrl());

        workspaceService.clearAuthenticationFiles(setup);
        assertThat(setup.sshPrivateKeyFile()).isNull();
        assertThat(setup.sshKnownHostsFile()).isNull();
        assertThat(privateKey).doesNotExist();
        assertThat(knownHosts).doesNotExist();
        workspaceService.cleanupWorkspace(setup);
    }

    @Test
    void createCredentialsFile_keepsTokenOutsideWorkspaceAndCleanupRemovesIt() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path workspace = setup.workspaceDir();

        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token", workspace);

        assertThat(credentials.getParent()).isEqualTo(workspace.getParent());
        assertThat(credentials.getFileName().toString()).startsWith("credentials-");
        assertThat(credentials).isNotEqualTo(workspace.resolve(".git-credentials"));
        assertThat(credentials).isNotEqualTo(workspace.resolveSibling("repository.credentials"));
        assertThat(Files.readString(credentials)).isEqualTo("https://oauth2:test-token@git.example.com\n");

        workspaceService.cleanupWorkspace(setup);

        assertThat(credentials).doesNotExist();
        assertThat(workspace.getParent()).doesNotExist();
    }

    @Test
    void authenticationFile_preservesConfiguredUsernameWithUppercaseScheme() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        RepositoryCredentials credentials = RepositoryCredentials.of(
                "https://api.bitbucket.org", "HTTPS://bitbucket.org", "alice", "app-password");

        workspaceService.createAuthenticationFiles(
                "HTTPS://bitbucket.org/owner/repo.git", credentials, setup);

        assertThat(Files.readString(setup.credentialsFile()))
                .isEqualTo("https://alice:app-password@bitbucket.org\n");
        workspaceService.cleanupWorkspace(setup);
    }

    @Test
    void createWorkspaceDirectory_usesConfiguredBaseDirectory() throws IOException {
        Path workspaceBaseDir = tempDir.resolve("sandbox-workspaces");
        workspaceService = new WorkspaceService(workspaceBaseDir.toString());

        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path workspace = setup.workspaceDir();

        assertThat(workspace.startsWith(workspaceBaseDir.toAbsolutePath().normalize())).isTrue();
        assertThat(workspace.getParent().getParent()).isEqualTo(workspaceBaseDir.toAbsolutePath().normalize());

        workspaceService.cleanupWorkspace(setup);

        assertThat(workspace).doesNotExist();
        assertThat(workspaceBaseDir).exists();
    }

    @Test
    void createCredentialsFile_rejectsWorkspaceWithoutPrivateParent() throws IOException {
        Path unmanagedWorkspace = tempDir.resolve("workspace");
        Files.createDirectories(unmanagedWorkspace);

        assertThatThrownBy(() -> workspaceService.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token", unmanagedWorkspace))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("private credential directory");
    }

    @Test
    void authorizationHeader_sendsTokenAsBasicHeaderWithoutCredentialStore() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        String remote = "https://dev.azure.com/org/project/_git/repo";
        setup.setAuthentication(remote,
                RepositoryCredentials.of("https://dev.azure.com", "https://dev.azure.com", "pat"), true);

        try {
            workspaceService.createAuthenticationFiles(remote, setup.repositoryCredentials(), setup);

            assertThat(setup.credentialsFile()).isNull();
            assertThat(workspaceService.gitConfigArgs(setup)).isEmpty();
            assertThat(workspaceService.authorizationHeaderEnvironment(setup)).containsExactlyInAnyOrderEntriesOf(
                    java.util.Map.of(
                            "GIT_CONFIG_COUNT", "1",
                            "GIT_CONFIG_KEY_0", "http." + remote + ".extraheader",
                            "GIT_CONFIG_VALUE_0", "Authorization: Basic "
                                    + java.util.Base64.getEncoder().encodeToString(":pat".getBytes())));
        } finally {
            workspaceService.cleanupWorkspace(setup);
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
    void authorizationHeader_notUsedByDefault() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        setup.setAuthentication("https://git.example.com/owner/repo.git",
                RepositoryCredentials.of("https://git.example.com", "https://git.example.com", "token"), false);

        try {
            assertThat(workspaceService.authorizationHeaderEnvironment(setup)).isEmpty();
        } finally {
            workspaceService.cleanupWorkspace(setup);
        }
    }

    @Test
    void gitConfigArgs_usesExternalCredentialStore() throws IOException {
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path workspace = setup.workspaceDir();
        Path credentials = workspaceService.createCredentialsFile(
                "https://git.example.com/owner/repo.git", null, "test-token", workspace);
        setup.setCredentialsFile(credentials);

        try {
            assertThat(workspaceService.gitConfigArgs(setup)).containsExactly(
                    "-c", "credential.helper=",
                    "-c", "credential.helper=store --file=" + credentials.toAbsolutePath());
        } finally {
            workspaceService.cleanupWorkspace(setup);
        }
    }

    @Test
    void commitAndPush_disablesWorkspaceHooks() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        WorkspaceSetup setup = workspaceService.createWorkspaceSetup();
        Path workspace = setup.workspaceDir();
        Files.createDirectories(workspace);
        initGitRepository(workspace);
        Path remote = tempDir.resolve("remote");
        Files.createDirectories(remote);
        runGit(remote, "init", "--bare");
        String branch = runGitCapture(workspace, "branch", "--show-current");
        runGit(workspace, "remote", "add", "origin", remote.toAbsolutePath().toString());
        runGit(workspace, "push", "-u", "origin", branch);
        setup.setAuthentication(remote.toString(),
                RepositoryCredentials.of("", remote.toString(), ""), false);

        Path hook = workspace.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(hook, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(workspace.resolve("README.md"), "changed");

        try {
            new Workspace(workspaceService, setup).commitAndPush(branch, "test commit",
                    "Test User", "test@example.com", false);
        } finally {
            workspaceService.cleanupWorkspace(setup);
        }
    }

    @Test
    void gitCommands_disableWorkspaceFsMonitor() throws Exception {
        initGitRepository(tempDir);
        Path monitorDirectory = tempDir.getParent().resolve(tempDir.getFileName() + "-fsmonitor");
        Files.createDirectories(monitorDirectory);
        Path marker = monitorDirectory.resolve("ran");
        Path monitor = monitorDirectory.resolve("monitor.sh");
        Files.writeString(monitor, "#!/bin/sh\ntouch " + marker + "\n");
        Assumptions.assumeTrue(Files.getFileStore(monitor).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(monitor, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        runGit(tempDir, "config", "core.fsmonitor", monitor.toString());

        runGit(tempDir, "status", "--porcelain");
        assertThat(marker).exists();
        Files.delete(marker);

        assertThat(workspaceService.runCommand(tempDir.toFile(),
                new String[]{"git", "status", "--porcelain"}, 10).success()).isTrue();
        assertThat(marker).doesNotExist();
    }

    private void initGitRepository(Path dir) throws IOException, InterruptedException {
        runGit(dir, "init");
        runGit(dir, "config", "user.email", "test@example.com");
        runGit(dir, "config", "user.name", "Test User");
        Files.writeString(dir.resolve("README.md"), "initial");
        runGit(dir, "add", "README.md");
        runGit(dir, "commit", "-m", "initial");
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

    private static final class FailOnceCleanupWorkspaceService extends WorkspaceService {
        private boolean fail = true;

        @Override
        void deleteDirectory(Path dir) throws IOException {
            if (fail) {
                fail = false;
                throw new IOException("simulated cleanup failure");
            }
            super.deleteDirectory(dir);
        }
    }
}
