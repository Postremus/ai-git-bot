package org.remus.giteabot.agent.validation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GitCommandTest {

    private static final Set<PosixFilePermission> EXECUTABLE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    @TempDir
    Path tempDir;

    @Test
    void run_disablesWorkspaceFsMonitor() throws Exception {
        Path repo = initRepository();
        Path monitorDirectory = tempDir.resolve("fsmonitor");
        Files.createDirectories(monitorDirectory);
        Path marker = monitorDirectory.resolve("ran");
        Path monitor = monitorDirectory.resolve("monitor.sh");
        Files.writeString(monitor, "#!/bin/sh\ntouch " + marker + "\n");
        Assumptions.assumeTrue(Files.getFileStore(monitor).supportsFileAttributeView("posix"));
        Files.setPosixFilePermissions(monitor, EXECUTABLE);
        git(repo, "config", "core.fsmonitor", monitor.toString());
        git(repo, "status", "--porcelain");
        assertThat(marker).exists();
        Files.delete(marker);

        CommandResult status = GitCommand.run(repo, 10, "status", "--porcelain");

        assertThat(status.success()).isTrue();
        assertThat(marker).doesNotExist();
    }

    @Test
    void run_disablesWorkspaceHooks() throws Exception {
        Path repo = initRepository();
        Assumptions.assumeTrue(Files.getFileStore(repo).supportsFileAttributeView("posix"));
        Path hook = repo.resolve(".git/hooks/pre-commit");
        Files.writeString(hook, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(hook, EXECUTABLE);
        Files.writeString(repo.resolve("README.md"), "changed");
        git(repo, "add", "README.md");

        CommandResult commit = GitCommand.run(repo, 15, "commit", "-m", "hooked");

        assertThat(commit.success()).as(commit.output()).isTrue();
    }

    @Test
    void run_reportsNonZeroExitWithOutput() throws Exception {
        CommandResult result = GitCommand.run(initRepository(), 10, "rev-parse", "does-not-exist");

        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("does-not-exist");
    }

    private Path initRepository() throws IOException, InterruptedException {
        Path repo = Files.createDirectories(tempDir.resolve("repo"));
        git(repo, "init", "-q");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test User");
        Files.writeString(repo.resolve("README.md"), "initial");
        git(repo, "add", "README.md");
        git(repo, "commit", "-q", "-m", "initial");
        return repo;
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}
