# Issue-Assigned Workflow Refactoring — Implementation Plan

> **Goal:** Replace the hardcoded `Bot.botType` (CODING/WRITER) dispatch for issue-assigned and issue-comment handling with a configurable, per-bot **issue-assigned workflow** mechanism modeled on the existing PR workflow configuration (`WorkflowConfiguration` / `PrWorkflowOrchestrator`), migrating the current coding and writer behaviors into two built-in issue workflows.

**Architecture:** A new `issueworkflow` feature package holds an `IssueWorkflow` SPI (mirror of `PrWorkflow`), a Spring-collected `IssueWorkflowRegistry`, and an `IssueWorkflowOrchestrator` that resolves the enabled issue workflow(s) from the bot's *issue-assigned* workflow configuration and owns the run lifecycle (outgoing `issueassignment.*` events + error recording), mirroring `PrWorkflowOrchestrator`. Persistence reuses the existing `workflow_configurations` / `workflow_selections` / `workflow_selection_params` tables with a new `kind` discriminator column (`PR` / `ISSUE`) — the same entity model serves both sides. Two built-in workflows, `issue-coding` and `issue-writer`, delegate internally to the existing `IssueImplementationService` and `WriterAgentService` (instantiated per bot via `AgentServiceFactory`), preserving current runtime behavior. `Bot.botType` is deprecated and removed from all dispatch logic and the UI, but the column is retained for one release as migration safety.

**Tech Stack:** Spring Boot 4.x, Java 21, Spring Data JPA, Flyway (H2 + PostgreSQL), Thymeleaf, JUnit 5 + Mockito + MockMvc, ArchUnit. No new dependencies (enforcer whitelist untouched).

---

## ADR-1: Reuse the `workflow_configurations` entity model with a `kind` discriminator

**Status:** Accepted

**Context**
A bot must reference a PR workflow configuration and an issue-assigned workflow configuration independently. The issue states a preference for using the same entity model as `PRWorkflow` configurations. Two options: separate parallel tables (`issue_workflow_configurations` + selection tables), or one shared model with a discriminator.

**Options Considered**
1. **Separate but analogous tables/entities for issue configurations**
   - ✅ Pros: Total isolation; no risk of PR queries accidentally returning issue configs.
   - ❌ Cons: Duplicates three tables, two entities, the selection/param plumbing, and large parts of `WorkflowConfigurationService` / `WorkflowSelectionService`; every future improvement (params validation, clone, details modal) must be implemented twice. Contradicts the issue's stated preference.
2. **Add `kind VARCHAR(16) NOT NULL DEFAULT 'PR'` to `workflow_configurations`; share all three tables and the service layer; add `Bot.issueWorkflowConfiguration` as a second FK to the same table**
   - ✅ Pros: Single model, single selection/params infrastructure; the admin UI (form, workflow-selection screen, details modal) is reused with a kind parameter; future kinds (e.g. scheduled workflows) come free.
   - ❌ Cons: Every existing read path (`findDefault()`, `findAll()`, deletion protection, available-workflow catalog) must become kind-aware; the "exactly one `default_entry`" invariant becomes "exactly one default *per kind*".

**Decision**
Choose **Option 2**. `WorkflowConfiguration` gains a `kind` field (`WorkflowConfigurationKind` enum: `PR`, `ISSUE`, stored as STRING). `Bot` gains `issueWorkflowConfiguration` (`@ManyToOne`, nullable, FK `issue_workflow_configuration_id`). All service methods that enumerate or resolve configurations take/derive a kind. `default_entry` is interpreted per kind: the existing `Default` row stays the PR default; the seeded coding-issue config becomes the ISSUE default.

**Consequences**
- `WorkflowSelection.workflowKey` values must be unique per kind, not globally: `issue-coding` / `issue-writer` must never collide with PR workflow keys (registry startup checks already reject collisions *within* a registry; the two registries are independent).
- Selection validation branches on kind: PR configs validate against `PrWorkflowRegistry`, ISSUE configs against `IssueWorkflowRegistry` (Task 6).
- The legacy fallback in `PrWorkflowOrchestrator.runAll` / `BotWebhookService.isWorkflowEnabled` (null PR config ⇒ only `review`) is unchanged.

## ADR-2: New `issueworkflow` package with an `IssueWorkflow` SPI covering assignment AND comment handling

**Status:** Accepted

**Context**
Issue behavior needs the same pluggability PR behavior got from `PrWorkflow`. Open question 3 of the issue asks whether issue-comment handling is formally part of the abstraction; the issue notes state comments must use the same configured workflow resolution. Package placement must respect `ArchitectureTest` (`..webhook` is a top layer — ArchUnit prefix matching also catches `webhooks`; `config` must stay feature-free).

**Options Considered**
1. **Reuse `PrWorkflow` for issues** (add issue methods to the existing SPI)
   - ✅ Pros: One SPI.
   - ❌ Cons: PR workflows would inherit meaningless issue methods (or vice versa); `PrWorkflowContext` carries PR-run lifecycle (run id, step appender, cancellation) that issue handling does not have; conflates two trigger domains.
2. **New `org.remus.giteabot.issueworkflow` package: `IssueWorkflow` SPI with `onIssueAssigned(IssueWorkflowContext)` and `onIssueComment(IssueWorkflowContext)`, plus `IssueWorkflowRegistry` and `IssueWorkflowOrchestrator`**
   - ✅ Pros: Clean mirror of the proven `prworkflow` architecture; comment handling is part of the contract from day one (both built-in workflows implement both methods); package name is ArchUnit-safe (no prefix collision with `webhook`, not referenced by the `config`/`session` rules).
   - ❌ Cons: Some structural duplication of registry/orchestrator scaffolding (accepted — the PR side was the template).

**Decision**
Choose **Option 2**. The SPI:

```java
public interface IssueWorkflow {
    String key();                 // stable kebab-case, persisted in workflow_selections
    String displayName();
    default String description() { return ""; }
    default WorkflowParamsSchema paramsSchema() { return WorkflowParamsSchema.empty(); } // reused from prworkflow
    void onIssueAssigned(IssueWorkflowContext context);
    void onIssueComment(IssueWorkflowContext context);
}
```

`IssueWorkflowContext` is a record carrying `Bot bot`, `WebhookPayload payload`, and the resolved, type-coerced params map (via the existing `WorkflowParamsValidator`/`WorkflowSelectionService.resolveParams`) so future workflows (chore-router) get parameterization for free. `onIssueComment` has no default no-op: both built-ins implement it, and future workflows must consciously decide their comment behavior.

**Consequences**
- `IssueWorkflowOrchestrator` resolves enabled keys from `bot.getIssueWorkflowConfiguration()` and runs them in stable (key-ordered) sequence, mirroring `PrWorkflowOrchestrator.runAll`. Seeded configurations contain exactly one selection each; enabling multiple issue workflows is possible but documented as an advanced case.
- A bot with a **null** issue configuration (only possible for rows created outside the migration/UI flow) logs and no-ops — the V39 migration backfills every existing bot, and the bot form defaults new bots to the ISSUE default configuration.
- The orchestrator owns the lifecycle currently inlined in `BotWebhookService.handleIssueAssigned`: publish `ISSUE_ASSIGNMENT_STARTED` before delegation (with issue title), `ISSUE_ASSIGNMENT_COMPLETED` on success, `ISSUE_ASSIGNMENT_FAILED` + `botService.recordError` on exception. Error handling and outgoing event publication are preserved verbatim (functional requirement 6).

## ADR-3: `botType` deprecated and inert, not dropped

**Status:** Accepted

**Context**
Open question 1: remove `botType` now or retain it. The issue notes say "botType should be deprecated first". Dropping the column in the same release that rewrites dispatch leaves no rollback path if a migrated bot misbehaves.

**Decision**
- `@Deprecated` on `Bot.botType` and the `BotType` enum; the `bots.bot_type` column stays (still `NOT NULL DEFAULT 'CODING'`).
- All dispatch decisions stop reading it (Tasks 8–9). The bot edit form stops rendering it; new bots keep the column default.
- `BotService.save` no longer forces `agentEnabled=false` for WRITER bots (that coupling dies with the type); `agentEnabled` becomes an independent toggle whose only consumer is the `issue-coding` workflow (same semantics as today's CODING branch).
- Column/enum removal is a follow-up issue after one release of runtime evidence.

**Consequences**
- `BotWebhookServiceTest` / `BotServiceTest` cases keyed on `botType` are rewritten against workflow configurations.
- Nothing reads `botType` at runtime after this change; keeping the column is pure migration safety.

## ADR-4: Writer-PR silence is preserved via an explicit empty PR configuration, not a type guard

**Status:** Accepted

**Context**
Today six PR-side handlers in `BotWebhookService` early-return on `botType == WRITER` (`reviewPullRequest`, `handleBotCommand`, `handlePrComment`, `handleInlineComment`, `handleReviewSubmitted`, `handlePrClosed`). This guard is load-bearing: V15 backfilled *every* bot — writers included — with the `Default` PR configuration (review enabled), so without the guard, writer bots would suddenly run PR reviews. Deleting `botType` requires an equivalent configuration-driven mechanism.

**Options Considered**
1. **Keep a boolean "handles PRs" flag on Bot**
   - ✅ Pros: Simple.
   - ❌ Cons: Re-introduces a category flag — exactly what this refactoring removes; duplicates what the PR configuration already expresses.
2. **Migration seeds an empty PR configuration (`No PR workflows`, kind `PR`, zero selections) and reassigns it to all WRITER bots; the PR handlers replace the type guard with config-driven checks**
   - ✅ Pros: Behavior is expressed purely through configuration; admins can later opt a writer bot into PR workflows by switching its PR config (a feature, not a bug); no entity change beyond ADR-1.
   - ❌ Cons: One more seeded row; each PR handler's guard must be re-derived carefully (see below).

**Decision**
Choose **Option 2**. Guard replacements, handler by handler:
- `reviewPullRequest` → delete the guard. `runAll` on an empty config already returns `List.of()` silently.
- `handleBotCommand` / `handlePrComment` → early-return (silently, no unrecognised-command reply) when the bot has **no enabled PR workflows at all**. This preserves writer silence *and* today's "not configured for reviews" semantics for non-empty configs.
- `handleInlineComment` / `handleReviewSubmitted` → delete the guard; the existing `agenticEnabled || reviewEnabled` checks already no-op on an empty config.
- `handlePrClosed` → replace the type guard with `isWorkflowEnabled(bot, ReviewWorkflow.KEY)` around the review-close call (today it runs `ReviewWorkflow` unconditionally for CODING bots — that behavior is kept for coding bots); the E2E close handler continues to run unconditionally (it is per-bot idempotent).

**Consequences**
- Zero behavioral delta for migrated writer bots on PR events; zero delta for coding bots.
- The seeded `No PR workflows` row is deletable-in-name only (not `default_entry`, but referenced by bots — the existing delete-protection for referenced configurations applies).

## ADR-5: Parallel admin controller for issue configurations, shared services and templates

**Status:** Accepted

**Context**
The issue requires a dedicated system-settings section with list/create/edit/manage-workflows screens modeled after `system-settings/workflow-configurations/...`.

**Options Considered**
1. **Extend `WorkflowConfigurationController` with a `kind` request param on every route**
   - ✅ Pros: One controller.
   - ❌ Cons: Every URL gains ambiguity; the two sections interleave in one list; deep links from the bot form's Details modal need kind context.
2. **New `IssueWorkflowConfigurationController` at `/system-settings/issue-workflow-configurations`, reusing the kind-aware services and reusing the existing templates parameterized by a `baseUrl`/`kind` model attribute**
   - ✅ Pros: Mirrors the house CRUD pattern (like `DeploymentTargetController`); clean section separation in `system-settings/list.html`; templates stay single-source.
   - ❌ Cons: A second, structurally similar controller class (boilerplate accepted — controllers are thin entry points per ArchUnit).

**Decision**
Choose **Option 2**. `system-settings/list.html` gains an "Issue-Assigned Workflow Configurations" section below the (renamed) "PR Workflow Configurations" section. The existing `workflow-configurations/form.html` and `workflows.html` templates are parameterized (title, base URL, catalog kind) rather than copied.

**Consequences**
- `WorkflowSelectionService.loadAvailableWorkflows` / `saveSelection` branch the catalog and validation on the configuration's kind, so the shared `workflows.html` renders `IssueWorkflow` entries for ISSUE configs.
- The bot form's workflow Details modal works for both selectors (the details endpoints are kind-agnostic — they resolve the configuration's kind server-side).

---

## Data Model & Migrations

### V38 — schema (both `h2/` and `postgresql/`, byte-identical, idempotent, V34 style)

```sql
ALTER TABLE workflow_configurations
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'PR';

ALTER TABLE bots
    ADD COLUMN IF NOT EXISTS issue_workflow_configuration_id BIGINT;

ALTER TABLE bots
    ADD CONSTRAINT IF NOT EXISTS fk_bots_issue_workflow_configuration
    FOREIGN KEY (issue_workflow_configuration_id) REFERENCES workflow_configurations(id);
```

### V39 — seed + data migration (both dialects, byte-identical, NOT EXISTS-guarded)

Order matters; every statement is re-runnable:

```sql
-- 1) Empty PR configuration for former writer bots (ADR-4)
INSERT INTO workflow_configurations (name, kind, default_entry, created_at, updated_at)
SELECT 'No PR workflows', 'PR', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM workflow_configurations WHERE name = 'No PR workflows');

-- 2) Coding-equivalent issue configuration — the ISSUE-kind default
INSERT INTO workflow_configurations (name, kind, default_entry, created_at, updated_at)
SELECT 'Issue: Coding Agent', 'ISSUE', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM workflow_configurations WHERE kind = 'ISSUE' AND default_entry = TRUE);

-- 3) Writer-equivalent issue configuration
INSERT INTO workflow_configurations (name, kind, default_entry, created_at, updated_at)
SELECT 'Issue: Writer Agent', 'ISSUE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM workflow_configurations WHERE name = 'Issue: Writer Agent');

-- 4) Workflow selections (one per built-in issue workflow)
INSERT INTO workflow_selections (workflow_configuration_id, workflow_key)
SELECT c.id, 'issue-coding' FROM workflow_configurations c
WHERE c.name = 'Issue: Coding Agent'
  AND NOT EXISTS (SELECT 1 FROM workflow_selections s
                  WHERE s.workflow_configuration_id = c.id AND s.workflow_key = 'issue-coding');

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key)
SELECT c.id, 'issue-writer' FROM workflow_configurations c
WHERE c.name = 'Issue: Writer Agent'
  AND NOT EXISTS (SELECT 1 FROM workflow_selections s
                  WHERE s.workflow_configuration_id = c.id AND s.workflow_key = 'issue-writer');

-- 5) Former writer bots: silence PR side (ADR-4), attach writer issue workflow
UPDATE bots SET workflow_configuration_id = (
    SELECT id FROM workflow_configurations WHERE name = 'No PR workflows' FETCH FIRST 1 ROWS ONLY)
WHERE bot_type = 'WRITER';

UPDATE bots SET issue_workflow_configuration_id = (
    SELECT id FROM workflow_configurations WHERE name = 'Issue: Writer Agent' FETCH FIRST 1 ROWS ONLY)
WHERE bot_type = 'WRITER' AND issue_workflow_configuration_id IS NULL;

-- 6) All other bots: attach the coding-equivalent (ISSUE default) workflow
UPDATE bots SET issue_workflow_configuration_id = (
    SELECT id FROM workflow_configurations WHERE kind = 'ISSUE' AND default_entry = TRUE FETCH FIRST 1 ROWS ONLY)
WHERE (bot_type IS NULL OR bot_type = 'CODING') AND issue_workflow_configuration_id IS NULL;
```

Note: step 2's guard keys on `kind + default_entry` (not name), so an install where an admin pre-created an ISSUE default does not get a duplicate. Step 5 intentionally runs before any admin could have reassigned configs — it is part of the same migration as the seed.

`src/test/resources/data.sql` currently seeds only `bot_tool_configurations`/`bot_tool_selections`; if the new integration tests need persisted configurations, add matching seed rows for the two ISSUE configs + selections (tests run with Flyway disabled).

---

## Tasks

### Task 1: Flyway V38 — schema
**Objective:** Add `kind` to `workflow_configurations` and `issue_workflow_configuration_id` to `bots`.
**Files:**
- Create `src/main/resources/db/migration/h2/V38__issue_workflow_configurations.sql`
- Create `src/main/resources/db/migration/postgresql/V38__issue_workflow_configurations.sql`

Match the V34 exemplar (both files byte-identical, `IF NOT EXISTS` on every statement). Verify with a startup against H2 and, if available, the compose PostgreSQL.

### Task 2: Flyway V39 — seed + bot data migration
**Objective:** Seed the `No PR workflows`, `Issue: Coding Agent` (ISSUE default), and `Issue: Writer Agent` configurations with their selections; reassign writer bots' PR config; backfill `issue_workflow_configuration_id` per prior `bot_type`.
**Files:**
- Create `src/main/resources/db/migration/h2/V39__issue_workflow_configurations_seed.sql`
- Create `src/main/resources/db/migration/postgresql/V39__issue_workflow_configurations_seed.sql`
- Modify `src/test/resources/data.sql` only if the new integration tests require seeded configs.

### Task 3: Entity model — kind discriminator + second bot reference + deprecation
**Objective:** JPA side of ADR-1/ADR-3.
**Files:**
- Create `src/main/java/org/remus/giteabot/prworkflow/config/WorkflowConfigurationKind.java` (`PR`, `ISSUE`)
- Modify `.../prworkflow/config/WorkflowConfiguration.java` — `@Enumerated(STRING) @Column(nullable=false) kind`, default `PR`; update class Javadoc (default-per-kind invariant)
- Modify `.../admin/Bot.java` — add `@ManyToOne @JoinColumn(name="issue_workflow_configuration_id") issueWorkflowConfiguration`; `@Deprecated` on `botType`
- Modify `.../admin/BotType.java` — `@Deprecated` + Javadoc pointing at issue workflow configurations

### Task 4: Kind-aware configuration services
**Objective:** All reads/writes of `WorkflowConfiguration` respect kind; default resolution is per kind.
**Files:**
- Modify `.../prworkflow/config/WorkflowConfigurationRepository.java` — `findByKind`, `findByKindAndDefaultEntryTrue`, kind-scoped name-exists checks
- Modify `.../prworkflow/config/WorkflowConfigurationService.java` — `findDefault(WorkflowConfigurationKind)`, `findAll(WorkflowConfigurationKind)`; keep existing no-arg methods delegating to `PR` for current callers; deletion/rename protection applies per kind's default; clone preserves kind
- Modify callers (`BotController`, `WorkflowConfigurationController`) to pass `PR` explicitly

### Task 5: `IssueWorkflow` SPI, context, and registry
**Objective:** The new extension point (ADR-2).
**Files:**
- Create `.../issueworkflow/IssueWorkflow.java` (SPI as sketched in ADR-2)
- Create `.../issueworkflow/IssueWorkflowContext.java` (record: `Bot bot`, `WebhookPayload payload`, `Map<String,Object> params`)
- Create `.../issueworkflow/IssueWorkflowRegistry.java` — mirror `PrWorkflowRegistry`: Spring-collected `List<IssueWorkflow>`, key-collision rejection on startup, `find`/`require`/`all`

Verify `ArchitectureTest` passes unchanged (no rule references `issueworkflow`; the package must not be added to the `config`/`session` restriction lists).

### Task 6: Kind-aware selection service
**Objective:** `WorkflowSelectionService` serves both catalogs.
**Files:**
- Modify `.../prworkflow/config/WorkflowSelectionService.java` — inject `IssueWorkflowRegistry`; branch `loadAvailableWorkflows`, `saveSelection`, `enableWorkflow`, `resolveParams`, `describeParams`, `isBooleanField` on the configuration's kind (PR → `PrWorkflowRegistry`, ISSUE → `IssueWorkflowRegistry`); `schemaFor` becomes kind-aware
- Modify `.../prworkflow/config/WorkflowSelectionRow.java` — generalize the row to carry either workflow type (e.g. keep `prWorkflow()` and add `issueWorkflow()`, or replace with display fields only; pick the smallest change that keeps `workflows.html` working for both)

Unknown persisted keys remain visible-but-flagged for both kinds, per the existing policy.

### Task 7: `IssueWorkflowOrchestrator`
**Objective:** Configuration-driven resolution + lifecycle ownership (events, error recording).
**Files:**
- Create `.../issueworkflow/IssueWorkflowOrchestrator.java`

Responsibilities, mirroring `PrWorkflowOrchestrator` minus run/step persistence (issue handling has no run tracking today — intentionally unchanged):
- `runAssigned(Bot, WebhookPayload)` / `runComment(Bot, WebhookPayload)`
- Resolve enabled keys via `WorkflowSelectionService.enabledWorkflowKeys(bot.getIssueWorkflowConfiguration().getId())`; null config or empty keys → debug log + return
- Per key: `registry.find` (warn + skip unregistered, same as PR side), resolve params via `resolveParams`, build context
- `runAssigned`: publish `ISSUE_ASSIGNMENT_STARTED` (with issue title) before, `ISSUE_ASSIGNMENT_COMPLETED` after; on exception publish `ISSUE_ASSIGNMENT_FAILED` (with error) and call `botService.recordError` — move `BotWebhookService.publishIssueEvent` here unchanged (envelope: issue number in the issue slot, `pullRequest` null)
- `runComment`: no outgoing events today — keep it that way; on exception only `botService.recordError` (current behavior)

### Task 8: Built-in workflows `issue-coding` / `issue-writer` (+ `AgentServiceFactory` as a bean)
**Objective:** Migrate current behaviors behind the SPI.
**Files:**
- Modify `.../admin/AgentServiceFactory.java` — promote from manually-constructed (`new` in `BotWebhookService`'s constructor) to a Spring `@Component` with `@RequiredArgsConstructor`, so both `BotWebhookService` and the workflow implementations inject it (constructor-injection only, per ArchUnit)
- Create `.../issueworkflow/coding/CodingIssueWorkflow.java` — key `issue-coding`, display name "Coding Agent"; `onIssueAssigned` keeps today's `bot.isAgentEnabled()` gate then delegates to `agentServiceFactory.createIssueImplementationService(bot).handleIssueAssigned(payload)`; `onIssueComment` same gate → `handleIssueComment(payload)`
- Create `.../issueworkflow/writer/WriterIssueWorkflow.java` — key `issue-writer`, display name "Writer Agent"; both methods delegate to `agentServiceFactory.createWriterAgentService(bot)` without an `agentEnabled` check (today's writer branch has none)

### Task 9: Rewire `BotWebhookService` and `BotService`
**Objective:** Remove every `botType` dispatch (acceptance criteria 1–2 + ADR-4).
**Files:**
- Modify `.../admin/BotWebhookService.java`:
  - `handleIssueAssigned` — keep `AiAuditContext` + `isCallerAllowed`; replace both type branches with `issueWorkflowOrchestrator.runAssigned(bot, payload)` (events/error recording now live in the orchestrator; keep the outer try/catch as defense-in-depth)
  - `handleIssueComment` — same via `runComment`
  - Delete `publishIssueEvent` (moved), `createWriterAgentService`; keep `createIssueImplementationService` only if still used by `handlePrComment`'s agent-session path (it is — leave untouched)
  - PR-side guards per ADR-4: delete WRITER early-returns in `reviewPullRequest`, `handleInlineComment`, `handleReviewSubmitted`; add silent "no enabled PR workflows → return" in `handleBotCommand`/`handlePrComment`; guard the review-close call in `handlePrClosed` with `isWorkflowEnabled(bot, ReviewWorkflow.KEY)`
- Modify `.../admin/BotService.java` — remove the `WRITER ⇒ agentEnabled=false` coupling

### Task 10: Bot edit form + list badges
**Objective:** Acceptance criteria 4–6.
**Files:**
- Modify `.../admin/BotController.java` — drop `botTypes` model attribute; add `issueWorkflowConfigurations` (kind `ISSUE`) and `prWorkflowConfigurations` (kind `PR`); bind new `issueWorkflowConfigurationId` request param (default: `findDefault(ISSUE)`); stop referencing `BotType`
- Modify `src/main/resources/templates/bots/form.html`:
  - Remove the `Bot Type` select and the `botTypeSelect` JS that hides `#agentEnabledContainer` (the agent toggle is always visible now — its only consumer is `issue-coding`)
  - Relabel `Workflow Configuration` → `PR Workflow Configuration` with help text ("Which workflows run on pull-request events")
  - Add `Issue-Assigned Workflow Configuration` select directly below (same markup + Details button/modal wiring, `issueWorkflowConfigurationId`), help text ("What this bot does when assigned to an issue, and how it handles follow-up issue comments")
- Modify `src/main/resources/templates/bots/list.html` — replace the Writer/Coding type badges with the issue workflow configuration name; the agent-enabled badge no longer keys on `botType` (`bot.agentEnabled` only)

### Task 11: System settings — issue-assigned workflow configuration section
**Objective:** Acceptance criterion 7 (ADR-5).
**Files:**
- Create `.../prworkflow/config/IssueWorkflowConfigurationController.java` at `/system-settings/issue-workflow-configurations`, mirroring `WorkflowConfigurationController` (new/edit/clone/save/delete/workflows + details JSON), hard-wired to kind `ISSUE`. It lives beside the existing config controller (not in `issueworkflow`) so all workflow-config admin UI stays in one place.
- Modify `.../prworkflow/config/WorkflowConfigurationController.java` — hard-wire kind `PR`, adjust page titles to "PR Workflow Configuration"
- Modify `src/main/resources/templates/system-settings/list.html` — rename existing section to "PR Workflow Configurations"; add "Issue-Assigned Workflow Configurations" section listing `ISSUE` configs with the same action links
- Modify `.../templates/system-settings/workflow-configurations/form.html` and `workflows.html` — parameterize title/base-URL/cancel-links via model attributes so both controllers reuse them

### Task 12: Documentation
**Objective:** Acceptance criterion 11.
**Files:**
- Create `doc/ISSUE_WORKFLOWS.md` — the mechanism, the two built-ins and their mapping to the old CODING/WRITER behavior, migration behavior (incl. the `No PR workflows` reassignment), how to add a future workflow (chore-router outlook)
- Modify `doc/PR_WORKFLOWS.md` — PR vs issue configuration split
- Modify `doc/WRITER_AGENT.md`, `doc/CODING_AGENT.md` — behavior is now selected via issue workflow configuration, not bot type
- Modify `doc/USER_GUIDE.md` / `doc/USING_THE_BOT.md` — bot setup walkthrough (two selectors)
- Modify `README.md` feature list + every `README.*.md` translation — per-house rule for user-facing features

---

## Test Plan

Read the existing tests in each package first and match their mocking/assertion style.

1. **Migration/data test** (H2, targeted Flyway versions per the data-migration reference recipe): a bot with `bot_type=CODING` ends up on `Issue: Coding Agent` with PR config untouched; a `WRITER` bot ends up on `Issue: Writer Agent` **and** `No PR workflows`; re-running is a no-op.
2. **Persistence test:** a `Bot` round-trips both `workflowConfiguration` and `issueWorkflowConfiguration` independently.
3. **`IssueWorkflowRegistryTest`:** key collision rejected; `require` throws on unknown key.
4. **`IssueWorkflowOrchestratorTest` (Mockito):** resolves keys from the bot's issue config; STARTED/COMPLETED events published with issue number/title; exception → FAILED event + `recordError`; null config / empty keys → silent no-op; unregistered key skipped.
5. **`BotWebhookServiceTest`:** `handleIssueAssigned`/`handleIssueComment` delegate to the orchestrator (no `botType` interaction — regression for acceptance criteria 1–2); writer-silence replacements in `handleBotCommand`/`handlePrComment` (empty PR config → no slash handlers, no unrecognised-command reply); `handlePrClosed` review call gated on review-enabled.
6. **Bot edit UI/controller test (MockMvc):** form renders no `botType` select, renders both workflow selectors; save binds `issueWorkflowConfigurationId`; default applied when absent.
7. **System-settings controller test (MockMvc):** create/edit/clone/delete ISSUE configs; PR-kind configs cannot be deleted/renamed via the ISSUE controller and vice versa; `workflows.html` for an ISSUE config lists `issue-coding`/`issue-writer` and validates params against `IssueWorkflow` schemas.
8. **Integration: migrated coding bot** assigned to an issue → `IssueImplementationService` path runs (spy/mock via `AgentServiceFactory`), events STARTED→COMPLETED. **Migrated writer bot** → `WriterAgentService` path runs. **Follow-up comment** routes through the same configured workflow.
9. **PR regression test:** PR events on a Default-config bot behave exactly as before (review runs; slash commands intact).
10. **Error-path test:** failing workflow → `recordError` + `issueassignment.failed` event (requirement 6).
11. Full `mvn verify` — only that runs `ArchitectureTest`.

## Assumptions

- Issue-comment handling is in scope and part of the `IssueWorkflow` contract from day one (issue notes).
- `botType` is deprecated, not removed (issue notes); column/enum removal is a follow-up.
- The same `workflow_configurations` entity model is used with a `kind` discriminator (issue preference).
- Both H2 and PostgreSQL tracks are updated; migrations follow the byte-identical V34 style.
- No new dependencies; the enforcer whitelist is untouched.
- `PrWorkflowRun` tracking is **not** introduced for issue workflows (current issue handling has none; adding it is a separate feature).

## Out of scope (per issue)

- Additional issue workflows beyond `issue-coding` / `issue-writer` (chore-router is a follow-up built on this SPI).
- PR workflow semantics changes beyond the shared-infrastructure kind awareness and the ADR-4 guard replacements.
- Dropping the `bots.bot_type` column / `BotType` enum.
