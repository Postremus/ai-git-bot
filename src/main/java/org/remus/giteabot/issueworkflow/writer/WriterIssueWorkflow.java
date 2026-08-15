package org.remus.giteabot.issueworkflow.writer;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.admin.AgentServiceFactory;
import org.remus.giteabot.issueworkflow.IssueWorkflow;
import org.remus.giteabot.issueworkflow.IssueWorkflowContext;
import org.springframework.stereotype.Component;

/**
 * Built-in issue-assigned workflow reproducing the legacy {@code WRITER} bot
 * behavior: delegates issue assignments and follow-up issue comments to a
 * per-bot {@code WriterAgentService}, which refines vague issues into
 * well-specified ones.
 */
@Component
@RequiredArgsConstructor
public class WriterIssueWorkflow implements IssueWorkflow {

    public static final String KEY = "issue-writer";

    private final AgentServiceFactory agentServiceFactory;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "Writer Agent";
    }

    @Override
    public String description() {
        return "Refines the assigned issue into a well-specified, testable issue with the writer"
                + " agent and answers follow-up questions in issue comments.";
    }

    @Override
    public void onIssueAssigned(IssueWorkflowContext context) {
        agentServiceFactory.createWriterAgentService(context.bot())
                .handleIssueAssigned(context.payload());
    }

    @Override
    public void onIssueComment(IssueWorkflowContext context) {
        agentServiceFactory.createWriterAgentService(context.bot())
                .handleIssueComment(context.payload());
    }
}
