package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowConfigurationControllerTest {

    private static WorkflowConfigurationController newController(
            WorkflowConfigurationService configurationService,
            WorkflowSelectionService selectionService) {
        return new WorkflowConfigurationController(configurationService, selectionService, messageSource());
    }

    private static org.springframework.context.MessageSource messageSource() {
        org.springframework.context.support.ResourceBundleMessageSource ms =
                new org.springframework.context.support.ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        return ms;
    }

    @Test
    void save_validationFailure_returnsFormWithError() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        doThrow(new IllegalArgumentException("Name is required"))
                .when(configurationService).save(any());
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.save(new WorkflowConfiguration(),
                mock(org.springframework.ui.Model.class), new RedirectAttributesModelMap());

        assertEquals("system-settings/workflow-configurations/form", view);
    }

    @Test
    void save_success_redirectsToWorkflowSelection() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration saved = new WorkflowConfiguration();
        saved.setId(42L);
        when(configurationService.save(any())).thenReturn(saved);
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.save(new WorkflowConfiguration(),
                mock(org.springframework.ui.Model.class), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/workflow-configurations/42/workflows", view);
    }

    @Test
    void delete_existingConfiguration_redirectsToSystemSettings() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(1L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(1L)).thenReturn(Optional.of(configuration));
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.delete(1L, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService).deleteById(1L);
    }

    @Test
    void delete_wrongKind_redirectsWithoutDeleting() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(1L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(1L)).thenReturn(Optional.of(configuration));
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.delete(1L, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService, never()).deleteById(1L);
    }

    @Test
    void saveWorkflowSelection_passesParamsThrough_andRedirects() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(3L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(3L)).thenReturn(Optional.of(configuration));
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        WorkflowConfigurationController controller = newController(
                configurationService, selectionService);
        MultiValueMap<String, String> allParams = new LinkedMultiValueMap<>();
        allParams.add("params.tests.command", "mvn test");
        allParams.add("params.tests.timeoutSeconds", "30");
        allParams.add("foo", "ignored");

        String view = controller.saveWorkflowSelection(3L,
                List.of("tests"), allParams, new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(selectionService).saveSelection(eq(3L), eq(List.of("tests")), any());
    }

    @Test
    void saveWorkflowSelection_wrongKind_redirectsWithoutSaving() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(3L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(3L)).thenReturn(Optional.of(configuration));
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        WorkflowConfigurationController controller = newController(
                configurationService, selectionService);

        String view = controller.saveWorkflowSelection(3L,
                List.of("tests"), new LinkedMultiValueMap<>(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(selectionService, never()).saveSelection(anyLong(), anyList(), any());
    }

    @Test
    void saveWorkflowSelection_delegatesParamExtractionToService() {
        // The "true-wins" / "last-value" extraction semantics live in
        // WorkflowSelectionService.extractWorkflowParams and are tested there.
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(7L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(7L)).thenReturn(Optional.of(configuration));
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        Map<String, Map<String, String>> extracted =
                Map.of("agentic-review", Map.of("enableFormalReviewDecision", "true"));
        when(selectionService.extractWorkflowParams(any(), anyList())).thenReturn(extracted);
        WorkflowConfigurationController controller = newController(
                configurationService, selectionService);

        MultiValueMap<String, String> allParams = new LinkedMultiValueMap<>();
        allParams.add("params.agentic-review.enableFormalReviewDecision", "false");
        allParams.add("params.agentic-review.enableFormalReviewDecision", "true");

        String view = controller.saveWorkflowSelection(7L, List.of("agentic-review"), allParams,
                new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(selectionService).saveSelection(7L, List.of("agentic-review"), extracted);
    }

    @Test
    void saveWorkflowSelection_validationError_redirectsBackToSelection() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(4L);
        configuration.setKind(WorkflowConfigurationKind.PR);
        when(configurationService.findById(4L)).thenReturn(Optional.of(configuration));
        WorkflowSelectionService selectionService = mock(WorkflowSelectionService.class);
        doThrow(new IllegalArgumentException("Workflow 'tests': Parameter 'Command' is required"))
                .when(selectionService).saveSelection(anyLong(), anyList(), any());
        WorkflowConfigurationController controller = newController(
                configurationService, selectionService);

        String view = controller.saveWorkflowSelection(4L, List.of("tests"),
                new LinkedMultiValueMap<>(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings/workflow-configurations/4/workflows", view);
    }

    @Test
    void editForm_wrongKind_redirectsToSystemSettings() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(2L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(2L)).thenReturn(Optional.of(configuration));
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.editForm(2L, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
    }

    @Test
    void cloneForm_wrongKind_redirectsWithoutCloning() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(2L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(2L)).thenReturn(Optional.of(configuration));
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.cloneForm(2L, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
        verify(configurationService, never()).cloneConfiguration(anyLong());
    }

    @Test
    void save_existingConfiguration_wrongKind_redirectsWithoutSaving() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(5L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(5L)).thenReturn(Optional.of(configuration));
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));
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
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(6L)).thenReturn(Optional.of(configuration));
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        String view = controller.workflowSelection(6L, new ExtendedModelMap(), new RedirectAttributesModelMap());

        assertEquals("redirect:/system-settings", view);
    }

    @Test
    void selectedWorkflows_wrongKind_returnsNotFound() {
        WorkflowConfigurationService configurationService = mock(WorkflowConfigurationService.class);
        WorkflowConfiguration configuration = new WorkflowConfiguration();
        configuration.setId(8L);
        configuration.setKind(WorkflowConfigurationKind.ISSUE);
        when(configurationService.findById(8L)).thenReturn(Optional.of(configuration));
        WorkflowConfigurationController controller = newController(
                configurationService, mock(WorkflowSelectionService.class));

        var response = controller.selectedWorkflows(8L);

        assertEquals(404, response.getStatusCode().value());
    }
}
