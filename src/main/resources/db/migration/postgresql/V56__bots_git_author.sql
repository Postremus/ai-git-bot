-- V56: Add the per-bot Git author identity used for commits the bot pushes
-- (issue agent, README sync, i18n coverage, unit tests, E2E suites).
-- Existing bots get the previous built-in identity "AI Agent <ai-agent@bot.local>".
ALTER TABLE bots ADD COLUMN IF NOT EXISTS git_author_name VARCHAR(255) NOT NULL DEFAULT 'AI Agent';
ALTER TABLE bots ADD COLUMN IF NOT EXISTS git_author_email VARCHAR(255) NOT NULL DEFAULT 'ai-agent@bot.local';
