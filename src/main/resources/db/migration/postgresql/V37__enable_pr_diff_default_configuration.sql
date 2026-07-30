-- Add the pr-diff context tool (on-demand diff hunk extraction for PR reviews)
-- to the default tool configuration so it is available to all bots immediately,
-- including on fresh installations where V30 found no agentic-review bots yet.
-- Avoids duplicate inserts via NOT EXISTS guard.

INSERT INTO bot_tool_selections (configuration_id, tool_name, tool_kind)
SELECT c.id, v.tool_name, v.tool_kind
FROM bot_tool_configurations c
CROSS JOIN (VALUES
    ('pr-diff', 'CONTEXT')
) AS v(tool_name, tool_kind)
WHERE c.default_entry = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM bot_tool_selections s
      WHERE s.configuration_id = c.id AND s.tool_name = v.tool_name
  );
