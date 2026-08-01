package org.remus.giteabot.issueworkflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Spring-managed lookup for all {@link IssueWorkflow} beans, mirroring
 * {@code org.remus.giteabot.prworkflow.PrWorkflowRegistry}.
 *
 * <p>Discovers all {@link IssueWorkflow} implementations via constructor
 * injection, validates that their {@link IssueWorkflow#key()} values are
 * unique (and lower-case kebab-case), and exposes them as both a key-indexed
 * map and an ordered list.</p>
 */
@Slf4j
@Service
public class IssueWorkflowRegistry {

    private final Map<String, IssueWorkflow> workflowsByKey = new LinkedHashMap<>();

    public IssueWorkflowRegistry(List<IssueWorkflow> workflows) {
        Map<String, IssueWorkflow> indexed = new LinkedHashMap<>();
        Map<String, String> seenLowercaseKeys = new HashMap<>();
        for (IssueWorkflow workflow : workflows) {
            String key = workflow.key();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("IssueWorkflow " + workflow.getClass().getName()
                        + " returned a blank key()");
            }
            String normalised = key.toLowerCase(Locale.ROOT);
            if (!normalised.equals(key)) {
                throw new IllegalStateException("IssueWorkflow key '" + key
                        + "' must be lower-case kebab-case (" + workflow.getClass().getName() + ")");
            }
            String previous = seenLowercaseKeys.put(normalised, workflow.getClass().getName());
            if (previous != null) {
                throw new IllegalStateException("Duplicate IssueWorkflow key '" + key + "' registered by "
                        + previous + " and " + workflow.getClass().getName());
            }
            indexed.put(normalised, workflow);
            log.info("Registered IssueWorkflow '{}' ({}) [{}]",
                    workflow.displayName(), key, workflow.getClass().getSimpleName());
        }
        workflowsByKey.putAll(indexed);
    }

    public Optional<IssueWorkflow> find(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(workflowsByKey.get(key.toLowerCase(Locale.ROOT)));
    }

    /**
     * Returns the workflow registered under {@code key} or throws if no such
     * workflow exists.
     */
    public IssueWorkflow require(String key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException(
                "No IssueWorkflow registered for key '" + key + "'"));
    }

    public List<IssueWorkflow> all() {
        return List.copyOf(workflowsByKey.values());
    }
}
