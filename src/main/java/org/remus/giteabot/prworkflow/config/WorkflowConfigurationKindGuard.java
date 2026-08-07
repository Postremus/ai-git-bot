package org.remus.giteabot.prworkflow.config;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Shared guard for workflow-configuration endpoints that validates a fetched
 * row's {@link WorkflowConfigurationKind} before the controller acts on it.
 * Kept package-private and stateless so both controllers can reuse it without
 * introducing a dependency between the controllers themselves.
 */
final class WorkflowConfigurationKindGuard {

    private WorkflowConfigurationKindGuard() {
        // utility class
    }

    /**
     * Fetches the configuration with the given id and returns it only when its
     * {@link WorkflowConfiguration#getKind()} matches the expected kind. If the
     * row is missing or of the wrong kind, a flash error is added and
     * {@code null} is returned.
     */
    static WorkflowConfiguration requireExpectedKind(
            Long id,
            WorkflowConfigurationKind expectedKind,
            WorkflowConfigurationService configurationService,
            RedirectAttributes redirectAttributes) {

        WorkflowConfiguration configuration = configurationService.findById(id).orElse(null);
        if (configuration == null) {
            redirectAttributes.addFlashAttribute("error", "Workflow configuration not found");
            return null;
        }
        if (configuration.getKind() != expectedKind) {
            redirectAttributes.addFlashAttribute("error",
                    "Workflow configuration is not of kind " + expectedKind.name());
            return null;
        }
        return configuration;
    }
}
