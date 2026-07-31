package org.remus.giteabot.prworkflow.config;

import lombok.RequiredArgsConstructor;

import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CRUD + clone for reusable {@link WorkflowConfiguration} rows. Mirrors
 * {@link org.remus.giteabot.systemsettings.BotToolConfigurationService} so the
 * admin UX (list / new / edit / delete / clone) stays uniform across both
 * "kinds" of bot configurations.
 *
 * <p>All enumeration and default-resolution is scoped by
 * {@link WorkflowConfigurationKind}: PR configurations drive pull-request
 * workflows, ISSUE configurations drive issue-assigned workflows. The
 * no-arg {@link #findAll()} and {@link #findDefault()} delegate to
 * {@link WorkflowConfigurationKind#PR} for the pre-existing callers.</p>
 *
 * <p>The default configuration of each kind is protected: it cannot be
 * renamed, deleted, nor have its default flag cleared. The default rows are
 * seeded by Flyway ({@code V15} for PR, {@code V39} for ISSUE); any
 * additional workflows shipped in later releases must add their own
 * follow-up migration — the application does not auto-extend defaults at
 * runtime.</p>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class WorkflowConfigurationService {

    private final WorkflowConfigurationRepository configurationRepository;
    private final WorkflowSelectionRepository selectionRepository;
    private final BotRepository botRepository;

    /**
     * All PR-kind configurations. Kept for pre-existing callers; new code
     * should call {@link #findAll(WorkflowConfigurationKind)} explicitly.
     */
    @Transactional(readOnly = true)
    public List<WorkflowConfiguration> findAll() {
        return findAll(WorkflowConfigurationKind.PR);
    }

    @Transactional(readOnly = true)
    public List<WorkflowConfiguration> findAll(WorkflowConfigurationKind kind) {
        return configurationRepository.findByKind(kind);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowConfiguration> findById(Long id) {
        return configurationRepository.findById(id);
    }

    /**
     * The default PR-kind configuration. Kept for pre-existing callers; new
     * code should call {@link #findDefault(WorkflowConfigurationKind)}.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowConfiguration> findDefault() {
        return findDefault(WorkflowConfigurationKind.PR);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowConfiguration> findDefault(WorkflowConfigurationKind kind) {
        return configurationRepository.findByKindAndDefaultEntryTrue(kind);
    }

    public WorkflowConfiguration save(WorkflowConfiguration configuration) {
        if (configuration.getName() == null || configuration.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        String trimmedName = configuration.getName().trim();

        if (configuration.getId() != null) {
            // Existing: update only name on the managed entity. The detached
            // entity from form binding has an empty selectedWorkflows list —
            // saving it directly would cascade-delete all persisted selections.
            WorkflowConfiguration existing = configurationRepository.findById(configuration.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
            if (existing.isDefaultEntry()) {
                if (!existing.getName().equals(trimmedName)) {
                    throw new IllegalArgumentException("The default workflow configuration cannot be renamed");
                }
            }
            if (configurationRepository.existsByNameAndIdNot(trimmedName, configuration.getId())) {
                throw new IllegalArgumentException("A workflow configuration with this name already exists");
            }
            existing.setName(trimmedName);
            return existing;
        }

        // New configurations are never default; the default is bootstrapped once.
        if (configurationRepository.existsByName(trimmedName)) {
            throw new IllegalArgumentException("A workflow configuration with this name already exists");
        }
        configuration.setName(trimmedName);
        configuration.setDefaultEntry(false);
        return configurationRepository.save(configuration);
    }

    /**
     * Creates an unsaved deep copy of the given configuration with a unique
     * {@code "Copy of …"} name and identical workflow selections. The caller
     * persists the result via {@link #save(WorkflowConfiguration)} or the
     * controller's create flow.
     */
    public WorkflowConfiguration cloneConfiguration(Long sourceId) {
        WorkflowConfiguration source = configurationRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
        WorkflowConfiguration clone = new WorkflowConfiguration();
        clone.setName(uniqueCopyName(source.getName()));
        clone.setKind(source.getKind());
        clone.setDefaultEntry(false);
        List<WorkflowSelection> clonedSelections = new ArrayList<>();
        for (WorkflowSelection original : source.getSelectedWorkflows()) {
            WorkflowSelection copy = new WorkflowSelection();
            copy.setConfiguration(clone);
            copy.setWorkflowKey(original.getWorkflowKey());
            copy.replaceParams(original.getParamsMap());
            clonedSelections.add(copy);
        }
        clone.setSelectedWorkflows(clonedSelections);
        return clone;
    }

    public void deleteById(Long id) {
        WorkflowConfiguration configuration = configurationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow configuration not found"));
        if (configuration.isDefaultEntry()) {
            throw new IllegalStateException("The default workflow configuration cannot be deleted");
        }
        List<Bot> bots = new ArrayList<>(botRepository.findByWorkflowConfigurationId(id));
        bots.addAll(botRepository.findByIssueWorkflowConfigurationId(id));
        if (!bots.isEmpty()) {
            String botNames = bots.stream().map(Bot::getName).distinct().toList().toString();
            throw new IllegalStateException("Workflow configuration is used by bot(s): " + botNames);
        }
        selectionRepository.deleteByConfigurationId(id);
        configurationRepository.delete(configuration);
    }

    private String uniqueCopyName(String baseName) {
        String candidate = "Copy of " + baseName;
        int suffix = 2;
        while (configurationRepository.existsByName(candidate)) {
            candidate = "Copy of " + baseName + " (" + suffix + ")";
            suffix++;
        }
        return candidate;
    }
}

