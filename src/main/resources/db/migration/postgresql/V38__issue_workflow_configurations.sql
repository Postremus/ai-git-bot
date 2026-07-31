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
-- See db/migration/h2/V38__issue_workflow_configurations.sql for the
-- conceptual notes; the Postgres flavour differs only in the constraint
-- guard (information_schema instead of ADD CONSTRAINT IF NOT EXISTS).

ALTER TABLE workflow_configurations
    ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'PR';

ALTER TABLE bots
    ADD COLUMN IF NOT EXISTS issue_workflow_configuration_id BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE constraint_name = 'fk_bots_issue_workflow_configuration'
          AND table_name = 'bots'
    ) THEN
        ALTER TABLE bots
            ADD CONSTRAINT fk_bots_issue_workflow_configuration
            FOREIGN KEY (issue_workflow_configuration_id) REFERENCES workflow_configurations(id);
    END IF;
END $$;
