package org.remus.giteabot.issueworkflow.triage;

import java.util.Set;

/**
 * One validated routing decision: the canonical assignee name plus the
 * one-line reason. Produced by {@link TriageAgentStrategy} when the model
 * emits its terminal answer and consumed by {@link IssueTriageService},
 * which posts the reason comment and performs the assignment.
 *
 * <p>The reserved value {@link #NONE_ASSIGNEE} means "post the reason but
 * leave the issue unassigned" and is never passed to the provider.</p>
 */
record RoutingDecision(String assignee, String reason) {

    /** Reserved assignment value: post the reason but leave the issue unassigned. */
    static final String NONE_ASSIGNEE = "none";

    /**
     * Validates untrusted model output and canonicalizes it: the assignment
     * must be one of the configured names (matched case-insensitively and
     * returned with the configured casing), the reason must be a non-blank
     * single line, and the model may not route the issue back to the triage
     * bot itself (that would retrigger the workflow in a loop).
     *
     * @throws InvalidOutput when any rule is violated
     */
    static RoutingDecision validate(String rawAssignee, String rawReason,
                                    Set<String> allowed, String botUsername) {
        if (rawAssignee == null || rawAssignee.isBlank()) {
            throw new InvalidOutput("missing assignment");
        }
        String canonical = allowed.stream()
                .filter(a -> a.equalsIgnoreCase(rawAssignee.trim()))
                .findFirst()
                .orElseThrow(() -> new InvalidOutput("unsupported assignment '" + rawAssignee + "'"));
        if (canonical.equalsIgnoreCase(botUsername)) {
            throw new InvalidOutput(
                    "refusing to self-assign to the triage bot '" + botUsername + "'");
        }
        if (rawReason == null || rawReason.isBlank()) {
            throw new InvalidOutput("missing reason");
        }
        String reason = rawReason.trim();
        if (reason.contains("\n") || reason.contains("\r")) {
            throw new InvalidOutput("reason must be a single line");
        }
        return new RoutingDecision(canonical, reason);
    }

    /** Model output that failed validation. */
    static final class InvalidOutput extends RuntimeException {
        InvalidOutput(String message) {
            super(message);
        }
    }
}
