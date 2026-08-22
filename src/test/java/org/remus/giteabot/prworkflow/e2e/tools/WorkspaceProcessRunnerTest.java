package org.remus.giteabot.prworkflow.e2e.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceProcessRunnerTest {

    private final WorkspaceProcessRunner runner = new WorkspaceProcessRunner();

    @Test
    void run_capturesOutputAndExitCode(@TempDir Path workspace) throws Exception {
        WorkspaceProcessRunner.ProcessResult result = runner.run(workspace,
                javaCommand(EchoProcess.class.getName(), "hello world"), 10_000, 1024);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.combinedOutput()).isEqualTo("hello world");
        assertThat(result.timedOut()).isFalse();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void run_appliesExtraEnvironment(@TempDir Path workspace) throws Exception {
        WorkspaceProcessRunner.ProcessResult result = runner.run(workspace,
                javaCommand(EnvDumpProcess.class.getName(), "MY_MARKER"),
                Map.of("MY_MARKER", "injected-value"), 10_000, 1024);

        assertThat(result.combinedOutput()).isEqualTo("injected-value");
    }

    @Test
    void run_inheritsAllowlistedToolchainEnvironment(@TempDir Path workspace) throws Exception {
        // PATH is part of the scrubbed-toolchain allowlist and must survive
        // so the child can resolve executables. Secret scrubbing itself is
        // covered by ProcessSupportTest.
        WorkspaceProcessRunner.ProcessResult result = runner.run(workspace,
                javaCommand(EnvDumpProcess.class.getName(), "PATH"), 10_000, 4096);

        assertThat(result.combinedOutput()).isNotBlank();
    }

    @Test
    void run_timesOutLongRunningCommands(@TempDir Path workspace) throws Exception {
        WorkspaceProcessRunner.ProcessResult result = runner.run(workspace,
                javaCommand(SleepyProcess.class.getName()), 500, 1024);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
    }

    @Test
    void run_boundsCombinedOutput(@TempDir Path workspace) throws Exception {
        WorkspaceProcessRunner.ProcessResult result = runner.run(workspace,
                javaCommand(VerboseProcess.class.getName()), 10_000, 64);

        assertThat(result.combinedOutput().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(64);
    }

    private static List<String> javaCommand(String className, String... args) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        java.util.List<String> command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), className));
        command.addAll(List.of(args));
        return command;
    }

    public static final class EchoProcess {
        public static void main(String[] args) {
            System.out.print(String.join(" ", args));
        }
    }

    public static final class EnvDumpProcess {
        public static void main(String[] args) {
            String value = System.getenv(args[0]);
            System.out.print(value != null ? value : "");
        }
    }

    public static final class SleepyProcess {
        public static void main(String[] args) throws InterruptedException {
            Thread.sleep(30_000);
        }
    }

    public static final class VerboseProcess {
        public static void main(String[] args) {
            System.out.print("x".repeat(4096));
        }
    }
}
