package org.remus.giteabot.prworkflow.e2e.tools;

import org.remus.giteabot.util.ProcessSupport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around {@link ProcessBuilder} for the {@code pr-test-run}
 * tool. Split into its own Spring bean so unit tests can substitute a stub
 * runner that returns a canned Playwright-style JSON report instead of
 * spawning an external process.
 *
 * <p>The runner intentionally combines stdout and stderr into a single
 * stream because most test frameworks (Playwright, pytest, k6) interleave
 * them, and the agent only needs a single textual blob to reason about the
 * outcome.</p>
 *
 * <p>Commands execute with a scrubbed environment (no application secrets)
 * and the strongest local lifetime control available, see
 * {@link ProcessSupport}.</p>
 */
@Component
public class WorkspaceProcessRunner {

    /** Result of one process invocation. */
    public record ProcessResult(int exitCode, String combinedOutput, long durationMs, boolean timedOut) { }

    /** Backwards-compatible overload — no extra environment overrides. */
    public ProcessResult run(Path workspace, List<String> command,
                             long timeoutMs, int maxOutputBytes) throws IOException, InterruptedException {
        return run(workspace, command, Map.of(), timeoutMs, maxOutputBytes);
    }

    /**
     * Runs the given command in {@code workspace}, capturing combined
     * stdout/stderr (UTF-8) up to {@code maxOutputBytes} bytes and waiting
     * at most {@code timeout} ms before terminating the process.
     *
     * @param extraEnv environment overrides applied on top of the scrubbed
     *                 environment (later entries win). Use this to inject
     *                 {@code BASE_URL} for browser tests or
     *                 {@code PLAYWRIGHT_JSON_OUTPUT_NAME} to route the report.
     */
    public ProcessResult run(Path workspace, List<String> command,
                             Map<String, String> extraEnv,
                             long timeoutMs, int maxOutputBytes) throws IOException, InterruptedException {
        long start = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(workspace.toFile())
                .redirectErrorStream(true);
        ProcessSupport.scrubEnvironment(pb);
        // Make sure CI-style envs do not break the runner with interactive prompts.
        pb.environment().put("CI", "1");
        if (extraEnv != null) {
            for (Map.Entry<String, String> e : extraEnv.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                pb.environment().put(e.getKey(), e.getValue());
            }
        }
        ProcessSupport.CommandResult result = ProcessSupport.run(pb, timeoutMs,
                TimeUnit.MILLISECONDS, maxOutputBytes);
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        return new ProcessResult(result.finished() ? result.exitCode() : -1,
                result.output(), durationMs, !result.finished());
    }
}
