package org.remus.giteabot.admin;

/**
 * @deprecated Hardcoded coding/writer categorization is replaced by
 * configuration-driven dispatch: issue behavior is selected via the bot's
 * issue-assigned {@code WorkflowConfiguration} (kind
 * {@code ISSUE}, see the {@code issueworkflow} package) and PR behavior via
 * its PR {@code WorkflowConfiguration} (kind {@code PR}). Retained for one
 * release as migration safety; scheduled for removal.
 */
@Deprecated(since = "1.19.0", forRemoval = true)
public enum BotType {
    CODING,
    WRITER
}
