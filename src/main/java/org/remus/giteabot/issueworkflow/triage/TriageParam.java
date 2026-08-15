package org.remus.giteabot.issueworkflow.triage;

import org.remus.giteabot.prworkflow.WorkflowParamName;

/**
 * Compile-time-safe parameter keys for {@link TriageIssueWorkflow}.
 */
public enum TriageParam implements WorkflowParamName {

    /** Editable routing prompt used to classify and route the issue. */
    SYSTEM_PROMPT("systemPrompt"),

    /** Comma-separated account names the workflow may assign issues to. */
    ASSIGNEES("assignees");

    private final String key;

    TriageParam(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}
