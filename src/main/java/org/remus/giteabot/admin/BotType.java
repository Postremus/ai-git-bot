package org.remus.giteabot.admin;

/**
 * @deprecated Hardcoded coding/writer categorization is replaced by
 * configuration-driven dispatch: issue behaviour is selected via the bot's
 * issue-assigned {@code WorkflowConfiguration} (kind
 * {@code ISSUE}, see the {@code issueworkflow} package) and PR behaviour via
 * its PR {@code WorkflowConfiguration} (kind {@code PR}). Retained for one
 * release as migration safety; scheduled for removal.
 */
@Deprecated
public enum BotType {
    CODING,
    WRITER
}
