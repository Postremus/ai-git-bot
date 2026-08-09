-- Issue-assigned workflow configurations: a workflow_configurations row can now
-- serve either pull-request events (kind='PR', the pre-existing behaviour) or
-- issue-assigned / issue-comment events (kind='ISSUE'). Bots reference both a
-- PR workflow configuration (workflow_configuration_id, since V14) and an
-- issue-assigned workflow configuration (issue_workflow_configuration_id,
-- added here) independently.
--
-- The default_entry flag is interpreted per kind: exactly one default PR
-- configuration (the existing 'Default' row) and exactly one default ISSUE
-- configuration (seeded by V39).
--
-- Idempotent per house convention (IF NOT EXISTS on every statement) so a
-- re-applied or manually pre-created schema never fails the migration.

ALTER TABLE workflow_configurations
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'PR';

ALTER TABLE bots
    ADD COLUMN IF NOT EXISTS issue_workflow_configuration_id BIGINT;

ALTER TABLE bots
    ADD CONSTRAINT IF NOT EXISTS fk_bots_issue_workflow_configuration
    FOREIGN KEY (issue_workflow_configuration_id) REFERENCES workflow_configurations(id);
