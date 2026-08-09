package org.remus.giteabot.issueworkflow.coding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AgentServiceFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.issueworkflow.IssueWorkflow;
import org.remus.giteabot.issueworkflow.IssueWorkflowContext;
import org.springframework.stereotype.Component;

/**
 * Built-in issue-assigned workflow reproducing the legacy {@code CODING} bot
 * behavior: delegates issue assignments and follow-up issue comments to a
 * per-bot {@code IssueImplementationService}.
 *
 * <p>The {@code agentEnabled} bot flag stays the gate for this workflow —
 * an agent-disabled bot logs and ignores issue events, exactly as the
 * legacy CODING branch of {@code BotWebhookService} did.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodingIssueWorkflow implements IssueWorkflow {

    public static final String KEY = "issue-coding";

    private final AgentServiceFactory agentServiceFactory;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "Coding Agent";
    }

    @Override
    public String description() {
        return "Implements the assigned issue with the coding agent (workspace, branch and pull"
                + " request) and continues the implementation on follow-up issue comments."
                + " Requires the bot's agent feature to be enabled.";
    }

    @Override
    public void onIssueAssigned(IssueWorkflowContext context) {
        Bot bot = context.bot();
        if (!bot.isAgentEnabled()) {
            log.debug("[Bot '{}'] Agent feature disabled, ignoring issue assignment", bot.getName());
            return;
        }
        agentServiceFactory.createIssueImplementationService(bot)
                .handleIssueAssigned(context.payload());
    }

    @Override
    public void onIssueComment(IssueWorkflowContext context) {
        Bot bot = context.bot();
        if (!bot.isAgentEnabled()) {
            log.debug("[Bot '{}'] Agent feature disabled, ignoring issue comment", bot.getName());
            return;
        }
        agentServiceFactory.createIssueImplementationService(bot)
                .handleIssueComment(context.payload());
    }
}
