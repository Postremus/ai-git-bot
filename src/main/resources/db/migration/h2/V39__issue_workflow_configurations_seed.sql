-- Issue-assigned workflow configurations: seed the two built-in issue
-- workflows and migrate existing bots off the deprecated bots.bot_type
-- dispatch.
--
-- Steps (idempotent, order matters):
--   1) 'No PR workflows' — an empty PR-kind configuration. Former WRITER bots
--      are reassigned to it in step 5: today the only thing stopping them
--      from running PR reviews is the bot_type guard in BotWebhookService
--      (V15 backfilled every bot, writers included, with the 'Default'
--      configuration). Once dispatch is configuration-driven, the empty
--      configuration preserves their PR-side silence.
--   2) 'Issue: Coding Agent' — the ISSUE-kind default configuration, holding
--      the built-in 'issue-coding' workflow (delegates to
--      IssueImplementationService, i.e. the previous CODING behaviour).
--      The guard keys on kind+default_entry (not name) so an install where an
--      admin pre-created an ISSUE default does not get a duplicate.
--   3) 'Issue: Writer Agent' — holds the built-in 'issue-writer' workflow
--      (delegates to WriterAgentService, i.e. the previous WRITER behaviour).
--   4) Workflow selections for both built-ins (NOT EXISTS-guarded).
--   5) Former WRITER bots: PR config -> 'No PR workflows',
--      issue config -> 'Issue: Writer Agent'.
--   6) All other bots: issue config -> the ISSUE default (coding-equivalent).
--
-- Mirroring the V15 policy, the application does NOT auto-extend these
-- configurations at runtime; future issue workflows are added by their own
-- follow-up Flyway scripts.

INSERT INTO workflow_configurations (name, kind, default_entry, created_at, updated_at)
SELECT 'No PR workflows', 'PR', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_configurations WHERE name = 'No PR workflows'
);

INSERT INTO workflow_configurations (name, kind, default_entry, created_at, updated_at)
SELECT 'Issue: Coding Agent', 'ISSUE', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_configurations WHERE kind = 'ISSUE' AND default_entry = TRUE
);

INSERT INTO workflow_configurations (name, kind, default_entry, created_at, updated_at)
SELECT 'Issue: Writer Agent', 'ISSUE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_configurations WHERE name = 'Issue: Writer Agent'
);

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key)
SELECT c.id, 'issue-coding'
FROM workflow_configurations c
WHERE c.name = 'Issue: Coding Agent'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.workflow_configuration_id = c.id
        AND s.workflow_key = 'issue-coding'
  );

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key)
SELECT c.id, 'issue-writer'
FROM workflow_configurations c
WHERE c.name = 'Issue: Writer Agent'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.workflow_configuration_id = c.id
        AND s.workflow_key = 'issue-writer'
  );

UPDATE bots
SET workflow_configuration_id = (
    SELECT id FROM workflow_configurations
    WHERE name = 'No PR workflows'
    FETCH FIRST 1 ROWS ONLY
)
WHERE bot_type = 'WRITER';

UPDATE bots
SET issue_workflow_configuration_id = (
    SELECT id FROM workflow_configurations
    WHERE name = 'Issue: Writer Agent'
    FETCH FIRST 1 ROWS ONLY
)
WHERE bot_type = 'WRITER'
  AND issue_workflow_configuration_id IS NULL;

UPDATE bots
SET issue_workflow_configuration_id = (
    SELECT id FROM workflow_configurations
    WHERE kind = 'ISSUE' AND default_entry = TRUE
    FETCH FIRST 1 ROWS ONLY
)
WHERE (bot_type IS NULL OR bot_type = 'CODING')
  AND issue_workflow_configuration_id IS NULL;
