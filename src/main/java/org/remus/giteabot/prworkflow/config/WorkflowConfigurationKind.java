package org.remus.giteabot.prworkflow.config;

/**
 * Discriminator for {@link WorkflowConfiguration} rows: which webhook-event
 * domain the configuration applies to.
 *
 * <ul>
 *   <li>{@link #PR} — pull-request events, resolved by
 *       {@code PrWorkflowOrchestrator} from {@code Bot.workflowConfiguration}
 *       (the pre-existing behaviour).</li>
 *   <li>{@link #ISSUE} — issue-assigned and issue-comment events, resolved by
 *       {@code IssueWorkflowOrchestrator} from
 *       {@code Bot.issueWorkflowConfiguration}.</li>
 * </ul>
 *
 * <p>Exactly one configuration per kind is flagged as the default entry;
 * the default is seeded by Flyway (V15 for {@link #PR}, V39 for
 * {@link #ISSUE}) and is protected against deletion/renaming.</p>
 */
public enum WorkflowConfigurationKind {
    PR,
    ISSUE
}
