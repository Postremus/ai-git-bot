package org.remus.giteabot.eventhook;

/**
 * Catalog of outgoing-webhook event types. The wire value is the
 * {@code eventType} string in the payload envelope (schema v1).
 */
public enum EventHookEventType {
    PR_WORKFLOW_STARTED("prworkflow.started"),
    PR_WORKFLOW_COMPLETED("prworkflow.completed"),
    PR_WORKFLOW_FAILED("prworkflow.failed"),
    AGENT_REVIEW_FINDING_DETECTED("prworkflow.agentreview.finding.detected"),
    ISSUE_ASSIGNMENT_STARTED("issueassignment.started"),
    ISSUE_ASSIGNMENT_COMPLETED("issueassignment.completed"),
    ISSUE_ASSIGNMENT_FAILED("issueassignment.failed");

    private final String wireValue;

    EventHookEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static EventHookEventType fromWireValue(String value) {
        for (EventHookEventType t : values()) {
            if (t.wireValue.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown event type: " + value);
    }
}
