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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueWorkflowConfigurationControllerTest {

    private static IssueWorkflowConfigurationController newController(
            WorkflowConfigurationService configurationService,
            WorkflowSelectionService selectionService) {
        return new IssueWorkflowConfigurationController(configurationService, selectionService, messageSource());
    }

    private static org.springframework.context.MessageSource messageSource() {
        org.springframework.context.support.ResourceBundleMessageSource ms =
                new org.springframework.context.support.ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        return ms;
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
        WorkflowConfiguration stored = new WorkflowConfiguration();
        stored.setId(42L);
        stored.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(42L)).thenReturn(Optional.of(stored));
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
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(7L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(7L)).thenReturn(Optional.of(configuration));
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        Map<String, Map<String, String>> extracted = Map.of("issue-coding", Map.of());
        when(selectionService.extractWorkflowParams(any(), anyList())).thenReturn(extracted);
        IssueWorkflowConfigurationController controller =
                newController(configurationService, selectionService);

        String view = controller.saveWorkflowSelection(7L, List.of("issue-coding"),
                new LinkedMultiValueMap<>(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(selectionService).saveSelection(7L, List.of("issue-coding"), extracted);
    }

    @Test
    void delete_existingConfiguration_redirectsToSystemSettings() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(1L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(1L)).thenReturn(Optional.of(configuration));
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        String view = controller.delete(1L, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService).deleteById(1L);
    }

    @Test
    void delete_wrongKind_redirectsWithoutDeleting() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(1L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(1L)).thenReturn(Optional.of(configuration));
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        String view = controller.delete(1L, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService, never()).deleteById(1L);
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

    @Test
    void selectedWorkflows_wrongKind_returnsNotFound() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(8L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(8L)).thenReturn(Optional.of(configuration));
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        var response = controller.selectedWorkflows(8L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void editForm_wrongKind_redirectsToSystemSettings() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(2L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(2L)).thenReturn(Optional.of(configuration));
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        String view = controller.editForm(2L, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
    }

    @Test
    void cloneForm_wrongKind_redirectsWithoutCloning() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(2L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(2L)).thenReturn(Optional.of(configuration));
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        String view = controller.cloneForm(2L, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService, never()).cloneConfiguration(any());
    }

    @Test
    void save_existingConfiguration_wrongKind_redirectsWithoutSaving() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(5L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(5L)).thenReturn(Optional.of(configuration));
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));
        WorkflowConfiguration update = new WorkflowConfiguration();
        update.setId(5L);
        update.setName("renamed");

        String view = controller.save(update, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService, never()).save(any());
    }

    @Test
    void workflowSelection_wrongKind_redirectsToSystemSettings() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(6L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(6L)).thenReturn(Optional.of(configuration));
        IssueWorkflowConfigurationController controller =
                newController(configurationService, mock(WorkflowSelectionService.class));

        String view = controller.workflowSelection(6L, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
    }
}
