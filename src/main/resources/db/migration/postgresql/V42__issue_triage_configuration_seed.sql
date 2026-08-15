-- Issue triage workflow: seed a ready-made, non-default ISSUE-kind
-- configuration holding the built-in 'issue-triage' workflow.
--
-- The workflow declares its parameters (systemPrompt, assignees) with
-- schema defaults, so no workflow_selection_params rows are seeded — the
-- defaults apply when params are resolved, and the admin can edit them on
-- the workflow-configuration page.
--
-- Idempotent, mirroring the V39 guards. The configuration is deliberately
-- NOT the kind default and no bots are reassigned: triage is opt-in.

INSERT INTO workflow_configurations (name, kind, default_entry, created_at, updated_at)
SELECT 'Issue: Triage', 'ISSUE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1 FROM workflow_configurations WHERE name = 'Issue: Triage'
);

INSERT INTO workflow_selections (workflow_configuration_id, workflow_key)
SELECT c.id, 'issue-triage'
FROM workflow_configurations c
WHERE c.name = 'Issue: Triage'
  AND NOT EXISTS (
      SELECT 1 FROM workflow_selections s
      WHERE s.workflow_configuration_id = c.id
        AND s.workflow_key = 'issue-triage'
  );
