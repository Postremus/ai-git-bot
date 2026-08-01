package org.remus.giteabot.prworkflow.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD UI for ISSUE-kind {@link WorkflowConfiguration} rows — the
 * issue-assigned workflow configurations selectable on the bot edit form.
 * Mirrors {@code WorkflowConfigurationController} (PR kind) and shares its
 * templates, parameterized via the {@code workflowConfig*} model attributes.
 *
 * <p>Controllers must not depend on each other (ArchUnit), so the small
 * template-attribute contract is duplicated here rather than shared.</p>
 */
@Slf4j
@Controller
@RequestMapping("/system-settings/issue-workflow-configurations")
@RequiredArgsConstructor
public class IssueWorkflowConfigurationController {

    private static final String BASE_URL = "/system-settings/issue-workflow-configurations";
    private static final String KIND_LABEL = "issue-assigned workflow";
    private static final String SELECTION_HELP_TEXT =
            "Tick the issue workflows that should run when a bot using this configuration is"
            + " assigned to an issue and when it receives follow-up issue comments. Usually"
            + " exactly one issue workflow is enabled; multiple enabled workflows run"
            + " sequentially in stable order (lexicographic by workflow key).";

    private static final WorkflowConfigurationKind EXPECTED_KIND = WorkflowConfigurationKind.ISSUE;

    private final WorkflowConfigurationService configurationService;
    private final WorkflowSelectionService selectionService;

    private static void addTemplateAttributes(Model model) {
        model.addAttribute("workflowConfigBaseUrl", BASE_URL);
        model.addAttribute("workflowConfigKindLabel", KIND_LABEL);
        model.addAttribute("workflowSelectionHelpText", SELECTION_HELP_TEXT);
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("workflowConfiguration", new WorkflowConfiguration());
        model.addAttribute("activeNav", "system-settings");
        addTemplateAttributes(model);
        return "system-settings/workflow-configurations/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        WorkflowConfiguration configuration = WorkflowConfigurationKindGuard.requireExpectedKind(id, EXPECTED_KIND, configurationService, redirectAttributes);
        if (configuration == null) {
            return "redirect:/system-settings";
        }
        model.addAttribute("workflowConfiguration", configuration);
        model.addAttribute("activeNav", "system-settings");
        addTemplateAttributes(model);
        return "system-settings/workflow-configurations/form";
    }

    @GetMapping("/{id}/clone")
    public String cloneForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        WorkflowConfiguration configuration = WorkflowConfigurationKindGuard.requireExpectedKind(id, EXPECTED_KIND, configurationService, redirectAttributes);
        if (configuration == null) {
            return "redirect:/system-settings";
        }
        try {
            WorkflowConfiguration clone = configurationService.cloneConfiguration(id);
            model.addAttribute("workflowConfiguration", clone);
            model.addAttribute("activeNav", "system-settings");
            addTemplateAttributes(model);
            return "system-settings/workflow-configurations/form";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/system-settings";
        }
    }

    @PostMapping("/save")
    public String save(@ModelAttribute("workflowConfiguration") WorkflowConfiguration workflowConfiguration,
                       Model model, RedirectAttributes redirectAttributes) {
        if (workflowConfiguration.getId() == null) {
            workflowConfiguration.setKind(EXPECTED_KIND);
        } else if (WorkflowConfigurationKindGuard.requireExpectedKind(workflowConfiguration.getId(), EXPECTED_KIND, configurationService, redirectAttributes) == null) {
            return "redirect:/system-settings";
        }
        try {
            WorkflowConfiguration saved = configurationService.save(workflowConfiguration);
            redirectAttributes.addFlashAttribute("success",
                    "Issue-assigned workflow configuration saved. Please select which workflows are enabled.");
            return "redirect:" + BASE_URL + "/" + saved.getId() + "/workflows";
        } catch (Exception e) {
            log.error("Failed to save issue-assigned workflow configuration", e);
            model.addAttribute("error", "Failed to save: " + e.getMessage());
            model.addAttribute("workflowConfiguration", workflowConfiguration);
            model.addAttribute("activeNav", "system-settings");
            addTemplateAttributes(model);
            return "system-settings/workflow-configurations/form";
        }
    }

    @GetMapping("/{id}/workflows")
    public String workflowSelection(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        WorkflowConfiguration configuration = WorkflowConfigurationKindGuard.requireExpectedKind(id, EXPECTED_KIND, configurationService, redirectAttributes);
        if (configuration == null) {
            return "redirect:/system-settings";
        }
        List<WorkflowSelectionRow> rows = selectionService.loadAvailableWorkflows(id);
        model.addAttribute("workflowConfiguration", configuration);
        model.addAttribute("workflows", rows);
        model.addAttribute("activeNav", "system-settings");
        addTemplateAttributes(model);
        return "system-settings/workflow-configurations/workflows";
    }

    @PostMapping("/{id}/workflows/save")
    public String saveWorkflowSelection(@PathVariable Long id,
                                        @RequestParam(name = "selectedWorkflowKeys", required = false)
                                        List<String> selectedWorkflowKeys,
                                        @RequestParam MultiValueMap<String, String> allParams,
                                        RedirectAttributes redirectAttributes) {
        if (WorkflowConfigurationKindGuard.requireExpectedKind(id, EXPECTED_KIND, configurationService, redirectAttributes) == null) {
            return "redirect:/system-settings";
        }
        try {
            Map<String, Map<String, String>> workflowParams =
                    selectionService.extractWorkflowParams(allParams, selectedWorkflowKeys);
            selectionService.saveSelection(id, selectedWorkflowKeys, workflowParams);
            redirectAttributes.addFlashAttribute("success", "Workflow selection saved successfully");
            return "redirect:/system-settings";
        } catch (Exception e) {
            log.error("Failed to save workflow selection", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save workflow selection: " + e.getMessage());
            return "redirect:" + BASE_URL + "/" + id + "/workflows";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (WorkflowConfigurationKindGuard.requireExpectedKind(id, EXPECTED_KIND, configurationService, redirectAttributes) == null) {
            return "redirect:/system-settings";
        }
        try {
            configurationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success",
                    "Issue-assigned workflow configuration deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete issue-assigned workflow configuration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/system-settings";
    }

    /**
     * Returns the selected workflows for the Bot Details modal (mirrors the
     * PR controller's endpoint; both are kind-agnostic because the
     * configuration's own kind decides which registry is consulted).
     */
    @GetMapping("/{id}/selected-workflows")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> selectedWorkflows(@PathVariable Long id) {
        WorkflowConfiguration configuration = configurationService.findById(id).orElse(null);
        if (configuration == null || configuration.getKind() != EXPECTED_KIND) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> rows = selectionService.describeSelections(id).stream()
                .map(row -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("workflowKey", row.workflowKey());
                    out.put("displayName", row.displayName());
                    out.put("category", row.category());
                    out.put("params", selectionService.describeParams(row.workflowKey(), row.persistedParams()));
                    return out;
                })
                .toList();
        return ResponseEntity.ok(rows);
    }
}
