# Sandboxed Command Execution — Review & Simplification Plan

> **Goal:** Review the Docker sandbox introduced on branch
> `CaeruleusAqua/scholle/sandboxed-command-execution-hardening` (commits `51859e0`, `c182504`, `9b1715d`
> on top of merge-base `83e6655`) and replace the copy-in/copy-out execution model with a
> lightweight read-write bind-mount dispatch that removes the `WORKSPACE_SETUP_COMMAND` shell
> script and the artifact return channel entirely.

**Scope note:** `git diff develop` is misleading for this branch — develop has moved ahead
(webhook signing, `WorkspaceSetup` credential-store hardening, payload-size filter). The true
branch surface is only 21 files; this review covers exactly that surface. Rebase interplay is
called out where relevant.

---

## 1. Summary of the as-built design

### Components added by the branch

| Component | Role |
|---|---|
| `agent/validation/SandboxedCommandExecutor.java` (439 lines, new) | Runs untrusted commands either directly (scrubbed env, `setsid` process group) or in an ephemeral `docker run --rm` container |
| `util/ProcessSupport.java` (280 lines, new) | Env scrubbing allowlists, bounded async output drain, descendant-process tracking/termination, UTF-8-safe decode |
| `config/AgentConfigProperties.SandboxConfig` | `agent.sandbox.*` — enabled, fallback-to-direct, image, network, memory/cpu/pids limits, workspace Mb, docker-host |
| `docker-compose.sandbox.yml` | Compose override: mounts `/var/run/docker.sock` + workspace root at identical host path, sets `AGENT_SANDBOX_*` env |
| `prworkflow/e2e/tools/WorkspaceProcessRunner.java` (rewritten) | Delegates to the executor; inflates the output budget by `ARTIFACT_CHANNEL_RESERVE_BYTES` and re-materializes base64 artifacts into the host workspace |
| `prworkflow/e2e/workspace/PrTestWorkspaceManager.java` | Defers `npm install` when sandboxed (tmpfs copy would discard `node_modules`); rejects E2E runs when `network=none` |

### Execution flow (sandboxed)

1. Host workspace is bind-mounted **read-only** at `/source`; `.git/config` is masked by an
   empty-file overlay mount.
2. The container gets a bounded tmpfs at `/ws` (`agent.sandbox.workspace-mb`, default 1024).
3. `WORKSPACE_SETUP_COMMAND` (a 28-line shell script compiled into a Java text block) runs as
   the container entrypoint and:
   - copies `/source/.` → `/ws/` (`cp -R`),
   - optionally runs `npm install -D "$AI_GIT_BOT_INSTALL_PACKAGE"` before the command,
   - wraps the command in `timeout --signal=KILL "$AI_GIT_BOT_TIMEOUT_SECONDS"s`,
   - captures output to `/tmp/command-output`,
   - when `AI_GIT_BOT_EXPORT_ARTIFACTS=true`, prints a 48 KiB head of the output, then a
     `__AI_GIT_BOT_ARTIFACTS__` marker, then `path<TAB>base64(content)` lines for files under
     `playwright-report`, `test-results`, `cypress/screenshots`, `cypress/videos`
     (≤ 4 MiB/file, ≤ 4 MiB total).
4. `WorkspaceProcessRunner` enlarges the captured-output budget by
   `ARTIFACT_CHANNEL_RESERVE_BYTES` (≈ 5.6 MiB), splits at the marker, base64-decodes each line
   and writes the files back into the **host** workspace, where `PrWorkflowToolExecutor`
   (`updatePlaywrightCases`, `attach-artifact`) reads them as usual.

### Consumers

- `ToolExecutionService.executeTool` — agent build/validation tools (`mvn`, `npm`, `go`, …).
- `WorkspaceProcessRunner` (via `PrWorkflowToolExecutor`) — E2E `pr-test-run` (Playwright,
  Cypress, pytest, k6), with `INSTALL_PACKAGE_ENV` injected for Playwright/Cypress.
- `PrTestWorkspaceManager.runNpmInstall` — scaffold-time dependency install.

---

## 2. Findings

### F1 — `WORKSPACE_SETUP_COMMAND` is a separation-of-concerns violation (confirmed)

One shell script embedded in `SandboxedCommandExecutor` carries **five** unrelated concerns:

1. workspace staging (`cp -R /source/. /ws/`),
2. dependency installation (`npm install -D …` — Playwright/Cypress framework knowledge),
3. timeout enforcement (`timeout --signal=KILL`),
4. output capture (`> /tmp/command-output`),
5. an artifact-export framing protocol (marker + base64 + size caps).

A generic executor in `agent.validation` should not know that Playwright needs
`@playwright/test@1.60.0`, which directories Cypress writes screenshots to, or how E2E
artifacts are framed. The npm hook is also framework-asymmetric: pytest and k6 get no
equivalent because they don't need one — a sign the hook lives at the wrong layer.

### F2 — Duplicated protocol constants across two languages

The artifact directory list (`playwright-report`, `test-results`, `cypress/screenshots`,
`cypress/videos`), the 4 MiB total cap and the 4 MiB per-file cap exist **both** in the shell
script (as magic numbers in a loop) and in `WorkspaceProcessRunner`
(`EXPORTED_ARTIFACT_DIRECTORIES`, `MAX_EXPORTED_ARTIFACT_BYTES`). Two sources of truth in two
languages that must stay in sync by hand — the Java side even validates paths the shell side
collected, so drift fails silently (artifacts dropped, not errors).

### F3 — The artifact return channel is fragile by construction

Artifacts travel as base64 inside the same bounded stdout stream as command output. Three
interacting budgets must stay consistent: the 48 KiB head-print inside the container, the
executor's `maxOutputBytes`, and `ARTIFACT_CHANNEL_RESERVE_BYTES` added by the runner
(commit `9b1715d` exists precisely because this broke once already). Truncation anywhere in
the chain silently corrupts or drops the marker tail.

### F4 — The tmpfs copy is the root cause of F1–F3, and it's expensive

Every sandboxed command starts with a full recursive copy of the workspace (including `.git`
and, for E2E workspaces, any `node_modules`). All package-manager caches are redirected to
per-container `/tmp`, so **nothing is warm**: Maven re-resolves, npm re-installs, Go
re-downloads on every single command. The non-persistence of `node_modules` is the *sole*
reason `INSTALL_PACKAGE_ENV` and the scaffold-time install deferral in
`PrTestWorkspaceManager` exist.

### F5 — Path skew between container and host confuses the agent

Commands run in `/ws` inside the container, so compiler/test errors reference `/ws/src/…`
while the agent's file tools (`cat`, `patch-file`, …) operate on the host path
(`/…/agent-workspace-XYZ/src/…`). The LLM has to mentally remap paths on every iteration.

### F6 — Divergence from current develop (rebase risk, not a defect)

The branch predates develop's workspace hardening: on develop, clone credentials live in a
credential-store file **outside** the workspace and never land in `.git/config`
(`WorkspaceSetup`), and host git commands use a trusted empty hooks directory. The branch's
base still embeds the token in the clone URL, which is why the `.git/config` overlay mask is
load-bearing here. After a rebase onto develop the overlay becomes defense-in-depth rather
than the primary credential guard — keep it, but the security story should be re-told in
`DEPLOYMENT.md`.

### F7 — Minor observations

- `resolveImage()` silently defaults to `tmseidel/ai-git-bot:latest`; a self-built deployment
  without `AGENT_SANDBOX_IMAGE` pulls a foreign registry image instead of failing fast.
- Timeout is enforced twice (Java deadline + `docker rm -f` in `finally`, **and** in-container
  `timeout`). The Java side alone is sufficient — it already kills the container.
- `probeDocker()` shells out to `docker version` on every `isSandboxed()` call
  (scaffold + every command). Cheap, but cacheable.
- `SandboxedCommandExecutor` sits in `agent.validation` but is equally a dependency of
  `prworkflow.e2e` — acceptable today (the dependency already exists), worth a comment.

---

## 3. The lightweight alternative

> **Core insight:** every complication above traces back to a single decision — mounting the
> workspace **read-only** and copying it into a bounded tmpfs. That decision protects an asset
> that is *already disposable*: agent workspaces are throwaway shallow clones
> (`WorkspaceService` deletes them after the run), E2E workspaces are throwaway scaffolds
> (`PrTestWorkspaceManager.cleanup`). The default non-sandboxed mode offers no such protection
> at all. The genuinely valuable protections — container boundary, network/cpu/memory limits,
> dropped capabilities, scrubbed environment, `.git/config` masking — do not depend on the copy.

Replace copy-in/copy-out with a **read-write bind mount at the identical absolute path**:

```
docker run --rm --name <n> --label ai-git-bot.sandbox=true \
  --network none --memory 2048m --cpus 2.0 --pids-limit 256 \
  --cap-drop ALL --security-opt no-new-privileges --log-driver none \
  --read-only --tmpfs /tmp:rw,size=<workspace-mb>m \
  --user 1000:1000 -e CI=1 [-e EXTRA=…] \
  --mount type=bind,src=<abs-workspace>,dst=<abs-workspace> \
  --mount type=bind,src=<empty-file>,dst=<abs-workspace>/.git/config,readonly \
  --mount type=bind,src=<empty-dir>,dst=<abs-workspace>/.git/hooks,readonly \
  -w <abs-workspace> <image> <command argv…>
```

No `--entrypoint`, no `sh -c`, no setup script — the tool argv is appended directly, exactly
like the direct-execution path. What this eliminates:

| Deleted | Why it's safe to delete |
|---|---|
| `WORKSPACE_SETUP_COMMAND` (28-line shell script) | Nothing to stage; nothing to frame |
| `INSTALL_PACKAGE_ENV` + npm hook + install deferral in `PrTestWorkspaceManager` | `node_modules` persists in the host workspace; scaffold-time `npm install` works as in direct mode |
| `EXPORT_ARTIFACTS_ENV`, `ARTIFACTS_MARKER`, base64 protocol | Build outputs land in the host workspace directly; `updatePlaywrightCases` / `attach-artifact` keep reading files from disk **unchanged** |
| `ARTIFACT_CHANNEL_RESERVE_BYTES`, `restoreArtifacts`, marker parsing in `WorkspaceProcessRunner` | No return channel to reserve or parse |
| `EXPORTED_ARTIFACT_DIRECTORIES` duplication (F2) | Only the consumer side needs the list — and it already has it implicitly (`attach-artifact` takes explicit paths) |
| In-container `timeout`, `AI_GIT_BOT_TIMEOUT_SECONDS` | Java deadline + `docker rm -f` in `finally` already guarantee termination (F7) |
| `48 KiB` head-print and its interaction with output budgets (F3) | Plain bounded output capture via `ProcessSupport.waitFor` |

What is **kept** (the actual security value): all isolation flags, env scrubbing +
`isEnvironmentName` allowlist, `.git/config` overlay, orphan reaping via label, bounded output,
fail-closed/fallback policy, `isNetworkIsolated()` config check.

Bonus effects:

- **F5 fixed:** container path == host path; error output references paths the agent's file
  tools understand.
- **F4 cost gone:** no `cp -R` per command; warm `node_modules`; optionally (follow-up) warm
  Maven/npm caches via a named volume instead of per-container `/tmp`.

### Security deltas vs. the tmpfs copy (must be mitigated or accepted)

1. **`.git/hooks` planting** — under a rw mount a malicious build can drop hooks that later
   *host-side* git commands (`branch-switcher`, `WorkspaceService` clone/fetch/push) would
   execute. Mitigation: read-only empty-dir mount over `.git/hooks` (one extra `--mount`,
   same pattern as the config overlay). Post-rebase, develop's trusted-hooks-path for host
   git commands is the second layer.
2. **Workspace content tampering** — the build can modify/delete workspace files. Accepted:
   workspaces are disposable, and this is exact parity with today's default direct mode.
3. **Disk bound** — `workspace-mb` no longer bounds build output; it is repurposed as the
   `/tmp` tmpfs size (build caches). Accepted: parity with direct mode, which has no disk
   bound either.
4. **UID ownership** — sandbox writes as `1000:1000`. In the compose deployment the app runs
   as `appuser` (1000:1000), so cleanup works. Bare-metal deployments where the JVM runs as a
   different UID would get root-owned-looking leftovers; document that sandboxed mode expects
   UID 1000 workspaces (compose override already enforces this).

---

## ADR-1: Read-write bind mount instead of tmpfs copy

**Status:** Proposed

**Context**
The read-only mount + tmpfs copy forces a staging copy, a dependency-install hook, and a
base64 artifact return channel — the three pieces that make the current design complex and
violate SoC (F1–F4).

**Options Considered**

1. **Keep copy-in/copy-out, move the shell script into the image as an entrypoint script**
   - ✅ Pros: `WORKSPACE_SETUP_COMMAND` leaves the Java file.
   - ❌ Cons: Every other problem stays — duplicated constants, fragile framing, cold caches,
     path skew, per-command full copy. Complexity moves, it doesn't shrink.
2. **Read-write bind mount at the identical host path, direct argv dispatch** (chosen)
   - ✅ Pros: Deletes the shell script, the artifact channel, the install hook and the output
     reserve arithmetic (~150 lines of Java + 28 lines of shell + 2 test classes' worth of
     protocol tests); warm `node_modules`; container paths match host paths; security posture
     is parity-plus (only deltas are §3 items 1–3, each mitigated or explicitly accepted).
   - ❌ Cons: Loses the disk bound and content immutability of the tmpfs copy — both of which
     the default direct mode never had; needs the `.git/hooks` mask as a new mount.

**Decision**
Choose **Option 2**.

**Consequences**
- `agent.sandbox.workspace-mb` is repurposed as the `/tmp` tmpfs size (build caches live
  there); the property stays, its semantics change — update `DEPLOYMENT.md`.
- `PrWorkflowToolExecutor.DEFAULT_RUN_OUTPUT_BYTES` reverts from 6 MiB to 256 KiB (the raise
  existed only to make room for the artifact channel).
- `.git/hooks` overlay becomes a required part of the mount set (ADR-3).

---

## ADR-2: Timeout enforcement in Java only

**Status:** Proposed

**Context**
Timeout is currently enforced twice: the Java deadline (`ProcessSupport.waitFor` + container
kill in `finally`) and an in-container `timeout --signal=KILL` driven by
`AI_GIT_BOT_TIMEOUT_SECONDS`. Two enforcers can disagree (rounding up to whole seconds in the
shell, clock skew on remote Docker hosts) and the env var is yet another channel the command
line must plumb through.

**Options Considered**

1. **Keep both layers** — ✅ defense in depth; ❌ redundant plumbing, rounding drift.
2. **Java deadline only** — ✅ single source of truth, `docker rm -f` is a SIGKILL that no
   container process survives; ❌ relies on Docker daemon responsiveness for the final kill
   (already true today for the `finally` path).

**Decision**
Choose **Option 2**. Drop `AI_GIT_BOT_TIMEOUT_SECONDS` and the `timeout` wrapper.

**Consequences**
- `buildDockerCommand` no longer needs timeout parameters at all — signature shrinks.

---

## ADR-3: `.git` hardening under a read-write mount

**Status:** Proposed

**Context**
With a rw workspace mount, `.git/config` remains masked by the existing empty-file overlay
(read *and* write protection), but `.git/hooks` becomes writable — a planted hook would run
on the host with the app user's privileges during subsequent host-side git operations.

**Options Considered**

1. **Mask `.git/hooks` with a read-only empty-dir mount** (chosen) — ✅ one `--mount` line,
   same lifecycle as the existing config overlay; ❌ one more temp dir per run.
2. **Rely solely on develop's `core.hooksPath` trusted-empty-hooks for host git commands** —
   ✅ zero new code here; ❌ couples the sandbox's safety to a develop-side feature and only
   covers git commands that remember to set it.

**Decision**
Choose **Option 1**, and treat Option 2 as welcome defense-in-depth after the rebase.

**Consequences**
- `createGitConfigOverlay` generalizes to a small "git overlays" holder (empty file for
  `config`, empty dir for `hooks`), created/deleted together per run.

---

## ADR-4: Artifact flow — read from the host workspace, delete the return channel

**Status:** Proposed

**Context**
E2E artifacts must reach the host because `attach-artifact` and `updatePlaywrightCases` read
them from `ctx.workspace()`. Today that requires the base64 return channel.

**Options Considered**

1. **Keep the base64 channel on top of the rw mount** — ❌ pure dead weight once files land
   on the host by themselves.
2. **Delete the channel; consumers read the workspace directly** (chosen) — ✅ the consumer
   code (`updatePlaywrightCases`, `attachArtifact`) needs *zero* changes; ❌ none.

**Decision**
Choose **Option 2**.

**Consequences**
- `WorkspaceProcessRunner` shrinks to a thin timeout/budget wrapper around the executor.
- `WorkspaceProcessRunnerTest` loses its marker-protocol cases; gains nothing — artifact
  restore is no longer its job.

---

## 4. Implementation Plan

### Components to modify

- [ ] `agent/validation/SandboxedCommandExecutor.java` — rewrite `buildDockerCommand`; delete
      `WORKSPACE_SETUP_COMMAND`, `INSTALL_PACKAGE_ENV`, `EXPORT_ARTIFACTS_ENV`,
      `ARTIFACTS_MARKER`, `AI_GIT_BOT_TIMEOUT_SECONDS`; generalize git overlay to config+hooks
- [ ] `prworkflow/e2e/tools/WorkspaceProcessRunner.java` — delete `restoreArtifacts`,
      `ARTIFACT_CHANNEL_RESERVE_BYTES`, `EXPORTED_ARTIFACT_DIRECTORIES`, reserve arithmetic
- [ ] `prworkflow/e2e/tools/PrWorkflowToolExecutor.java` — delete `INSTALL_PACKAGE_ENV`
      injection; revert `DEFAULT_RUN_OUTPUT_BYTES` to `256 * 1024`
- [ ] `prworkflow/e2e/workspace/PrTestWorkspaceManager.java` — delete the `isSandboxed()`
      install deferral in `runNpmInstall` (always run the install); keep the
      `isNetworkIsolated()` guard
- [ ] `config/AgentConfigProperties.SandboxConfig` — re-document `workspaceMb` as `/tmp` tmpfs
      size; consider fail-fast when sandbox enabled but image blank (instead of the
      `tmseidel/ai-git-bot:latest` default — F7)
- [ ] Tests: `SandboxedCommandExecutorTest` (new mount assertions), `WorkspaceProcessRunnerTest`
      (drop protocol cases), `PrTestWorkspaceManagerSandboxTest` (drop deferral case),
      `PrWorkflowToolExecutorTest` (drop `INSTALL_PACKAGE_ENV` assertion)
- [ ] `doc/DEPLOYMENT.md` — rewrite the sandbox section (rw mount, same-path requirement, git
      masks, UID-1000 expectation, disk-bound trade-off)
- [ ] `docker-compose.sandbox.yml` — unchanged (already mounts the workspace root at the
      identical path); verify only

### Sequence

1. Rewrite `buildDockerCommand` (ADR-1..3): rw bind mount at `workspaceDir.toAbsolutePath()`,
   `-w` same path, config overlay + new hooks overlay mounts, `/tmp` tmpfs sized by
   `workspace-mb`, drop `--entrypoint`/`sh -c`/setup script, append command argv directly,
   drop timeout params (ADR-2).
2. Generalize `createGitConfigOverlay` → per-run overlay holder (empty file + empty dir),
   deleted in the existing `finally`.
3. Delete the artifact channel end-to-end (ADR-4): runner, executor constants, executor-side
   env injection in `PrWorkflowToolExecutor`.
4. Simplify `PrTestWorkspaceManager.runNpmInstall`.
5. Update the four test classes; add an assertion that the docker argv **ends with** the tool
   argv (no shell wrapper) and contains both `.git` masks.
6. Docs: `DEPLOYMENT.md` sandbox section + ADR status flips in this file.
7. Verify: `mvn test-compile surefire:test -Dtest='SandboxedCommandExecutorTest,WorkspaceProcessRunnerTest,PrTestWorkspaceManager*,PrWorkflowToolExecutorTest,ToolExecutionService*'`
   (plus `ArchitectureTest` — package placement unchanged, but the run is cheap).

### Assumptions

- Workspaces (agent clones and E2E scaffolds) are disposable; build-time tampering with them
  is acceptable (parity with the default direct mode).
- The app deployment can guarantee UID 1000 workspace ownership (compose override already
  does; bare-metal gets a doc note).
- The branch will be rebased onto current develop; develop's credential-store hardening and
  trusted-hooks-path then complement (not replace) the `.git` masks.
- Out of scope (possible follow-up): named-volume caches for Maven/npm/Go to get warm builds
  across runs; caching the `docker version` probe.
