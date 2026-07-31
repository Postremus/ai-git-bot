package org.remus.giteabot.prworkflow.config;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable named whitelist of workflows that may run on a bot's webhook
 * events — {@link org.remus.giteabot.prworkflow.PrWorkflow PrWorkflows} for
 * pull-request events ({@link WorkflowConfigurationKind#PR}) or
 * {@code IssueWorkflows} for issue-assigned/issue-comment events
 * ({@link WorkflowConfigurationKind#ISSUE}). Analogous to
 * {@link org.remus.giteabot.systemsettings.BotToolConfiguration}.
 *
 * <p>Exactly one configuration <em>per {@link #getKind() kind}</em> is
 * flagged as the {@link #isDefaultEntry() default entry}; the PR default row
 * is seeded by Flyway migration {@code V15__workflow_configurations_default.sql}
 * and the ISSUE default by {@code V39__issue_workflow_configurations_seed.sql}.
 * Default rows are protected against deletion/renaming.</p>
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "workflow_configurations")
public class WorkflowConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    /**
     * Which webhook-event domain this configuration applies to. Defaults to
     * {@link WorkflowConfigurationKind#PR} so existing rows and callers keep
     * their pull-request semantics; issue-assigned configurations are created
     * with {@link WorkflowConfigurationKind#ISSUE}.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkflowConfigurationKind kind = WorkflowConfigurationKind.PR;

    /** Marks the single, non-deletable default configuration of its kind. */
    @Column(name = "default_entry", nullable = false)
    private boolean defaultEntry;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "configuration", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<WorkflowSelection> selectedWorkflows = new ArrayList<>();

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}

