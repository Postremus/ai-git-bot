package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.util.ProcessSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git} for a workspace with the environment scrubbed and every
 * repository-controlled execution hook (hooks, fsmonitor, credential helpers,
 * system and global config) disabled, since untrusted code may have run in it.
 */
@Slf4j
final class GitCommand {

    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private GitCommand() {
    }

    static CommandResult run(Path workDir, int timeoutSeconds, String... gitArgs) {
        return run(workDir, timeoutSeconds, Map.of(), gitArgs);
    }

    static CommandResult run(Path workDir, int timeoutSeconds, Map<String, String> extraEnvironment,
                             String... gitArgs) {
        Path disabledHooksDirectory = null;
        Path emptyGlobalGitConfig = null;
        try {
            // Git reads repository-controlled configuration after untrusted code ran in the workspace.
            disabledHooksDirectory = Files.createTempDirectory("ai-git-bot-empty-hooks-");
            emptyGlobalGitConfig = Files.createTempFile(disabledHooksDirectory, "global-", ".gitconfig");
            List<String> gitCommand = new ArrayList<>(gitArgs.length + 7);
            gitCommand.add("git");
            gitCommand.add("-c");
            gitCommand.add("core.hooksPath=" + disabledHooksDirectory.toAbsolutePath().normalize());
            gitCommand.add("-c");
            gitCommand.add("core.fsmonitor=false");
            gitCommand.add("-c");
            gitCommand.add("credential.helper=");
            gitCommand.addAll(Arrays.asList(gitArgs));
            ProcessBuilder pb = new ProcessBuilder(gitCommand);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            ProcessSupport.scrubEnvironmentForGit(pb);
            pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
            pb.environment().put("GIT_CONFIG_GLOBAL", emptyGlobalGitConfig.toString());
            pb.environment().putAll(extraEnvironment);

            ProcessSupport.CommandResult result = ProcessSupport.run(
                    pb, timeoutSeconds, TimeUnit.SECONDS, MAX_OUTPUT_BYTES);
            if (!result.finished()) {
                return new CommandResult(false,
                        "Command timed out after " + timeoutSeconds + " seconds");
            }
            return new CommandResult(result.exitCode() == 0, result.output());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command was interrupted", e);
        } catch (IOException e) {
            log.error("Failed to run command: {}", e.getMessage());
            return new CommandResult(false, "Exception: " + e.getMessage());
        } finally {
            deleteQuietly(emptyGlobalGitConfig, "empty global Git config");
            deleteQuietly(disabledHooksDirectory, "empty Git hooks directory");
        }
    }

    private static void deleteQuietly(Path path, String description) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to remove {} {}: {}", description, path, e.getMessage());
        }
    }
}
