-- Issue-assigned workflow configurations: seed the two built-in issue
-- workflows and migrate existing bots off the deprecated bots.bot_type
-- dispatch.
--
-- See db/migration/h2/V39__issue_workflow_configurations_seed.sql for the
-- conceptual notes; the Postgres flavour differs only in LIMIT syntax.

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
    LIMIT 1
)
WHERE bot_type = 'WRITER';

UPDATE bots
SET issue_workflow_configuration_id = (
    SELECT id FROM workflow_configurations
    WHERE name = 'Issue: Writer Agent'
    LIMIT 1
)
WHERE bot_type = 'WRITER'
  AND issue_workflow_configuration_id IS NULL;

UPDATE bots
SET issue_workflow_configuration_id = (
    SELECT id FROM workflow_configurations
    WHERE kind = 'ISSUE' AND default_entry = TRUE
    LIMIT 1
)
WHERE (bot_type IS NULL OR bot_type = 'CODING')
  AND issue_workflow_configuration_id IS NULL;
