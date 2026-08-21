-- V45: Persist raw LLM request/response bodies in the AI usage audit log
-- so the /usage page can show the full payloads for each interaction.
-- Both additions are idempotent so the migration can be re-applied safely.

ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS raw_request TEXT;
ALTER TABLE ai_usage_log ADD COLUMN IF NOT EXISTS raw_response TEXT;
