package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueWorkflowConfigurationControllerTest {

    private static IssueWorkflowConfigurationController newController(
            WorkflowConfigurationService configurationService,
            WorkflowSelectionService selectionService) {
        return new IssueWorkflowConfigurationController(configurationService, selectionService);
    }

    @Test
    void save_newConfiguration_forcesIssueKind_andRedirectsToSelection() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration saved = new WorkflowConfiguration();
        saved.setId(42L);
        when(configurationService.save(any())).thenReturn(saved);
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        String view = controller.save(new WorkflowConfiguration(),
                new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/issue-workflow-configurations/42/workflows", view);
        ArgumentCaptor<WorkflowConfiguration> captor = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(configurationService).save(captor.capture());
        assertEquals(WorkflowConfigurationKind.ISSUE, captor.getValue().getKind());
    }

    @Test
    void save_existingConfiguration_keepsStoredKind() {
        // Updates bind a detached entity whose kind defaults to PR; the
        // controller must not stamp ISSUE onto it (the service only copies
        // the name onto the managed entity, which keeps its stored kind).
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration saved = new WorkflowConfiguration();
        saved.setId(42L);
        when(configurationService.save(any())).thenReturn(saved);
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        WorkflowConfiguration existing = new WorkflowConfiguration();
        existing.setId(42L);
        existing.setKind(WorkflowConfigurationKind.PR); // form-binding default

        String view = controller.save(existing, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/issue-workflow-configurations/42/workflows", view);
        ArgumentCaptor<WorkflowConfiguration> captor = ArgumentCaptor.forClass(WorkflowConfiguration.class);
        verify(configurationService).save(captor.capture());
        assertEquals(WorkflowConfigurationKind.PR, captor.getValue().getKind());
    }

    @Test
    void saveWorkflowSelection_delegatesExtraction_andRedirects() {
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        Map<String, Map<String, String>> extracted = Map.of("issue-coding", Map.of());
        when(selectionService.extractWorkflowParams(any(), anyList())).thenReturn(extracted);
        IssueWorkflowConfigurationController controller =
                newController(mock(WorkflowConfigurationService.class), selectionService);

        String view = controller.saveWorkflowSelection(7L, List.of("issue-coding"),
                new LinkedMultiValueMap<>(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(selectionService).saveSelection(7L, List.of("issue-coding"), extracted);
    }

    @Test
    void delete_redirectsToSystemSettings() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        String view = controller.delete(1L, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService).deleteById(1L);
    }

    @Test
    void selectedWorkflows_missingConfiguration_returnsNotFound() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        when(configurationService.findById(99L)).thenReturn(Optional.empty());
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        var response = controller.selectedWorkflows(99L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void workflowSelection_rendersSharedTemplate_withIssueTemplateAttributes() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(3L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(3L)).thenReturn(Optional.of(configuration));
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        ExtendedModelMap model = new ExtendedModelMap();
        String view = controller.workflowSelection(3L, model, new RedirectAttributesModelMap());

        assertEquals("system-settings/workflow-configurations/workflows", view);
        assertEquals("/system-settings/issue-workflow-configurations",
                model.getAttribute("workflowConfigBaseUrl"));
        assertEquals("issue-assigned workflow", model.getAttribute("workflowConfigKindLabel"));
    }
}
