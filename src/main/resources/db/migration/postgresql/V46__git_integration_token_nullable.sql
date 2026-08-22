-- V46: Allow git_integrations.token to be NULL so the stored token can be
-- explicitly cleared from the admin UI (matching ai_integrations.api_key).
ALTER TABLE git_integrations ALTER COLUMN token DROP NOT NULL;
