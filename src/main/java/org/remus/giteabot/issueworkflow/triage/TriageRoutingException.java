package org.remus.giteabot.issueworkflow.triage;

/**
 * Signals a triage/routing failure that must surface through the existing
 * issue-workflow error handling: the {@code IssueWorkflowOrchestrator}
 * records a bot error and publishes {@code issueassignment.failed}. The
 * user-facing explanation is posted as an issue comment before this
 * exception is thrown.
 */
public class TriageRoutingException extends RuntimeException {

    public TriageRoutingException(String message) {
        super(message);
    }

    public TriageRoutingException(String message, Throwable cause) {
        super(message, cause);
    }
}
