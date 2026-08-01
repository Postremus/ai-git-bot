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
 * CRUD UI for PR-kind {@link WorkflowConfiguration} rows. Mirrors the
 * structure of the MCP / Bot-tool configuration controllers
 * (list-on-System-settings, dedicated form + sub-page for the workflow
 * selection). The issue-assigned counterpart is
 * {@link IssueWorkflowConfigurationController}; both share the same
 * templates, parameterized via the {@code workflowConfig*} model attributes.
 */
@Slf4j
@Controller
@RequestMapping("/system-settings/workflow-configurations")
@RequiredArgsConstructor
public class WorkflowConfigurationController {

    static final String BASE_URL = "/system-settings/workflow-configurations";
    static final String KIND_LABEL = "PR workflow";
    static final String SELECTION_HELP_TEXT =
            "Tick the PR workflows that should run on every pull-request webhook for bots using"
            + " this configuration. Workflows are executed sequentially in stable order"
            + " (lexicographic by workflow key).";

    private static final WorkflowConfigurationKind EXPECTED_KIND = WorkflowConfigurationKind.PR;

    private final WorkflowConfigurationService configurationService;
    private final WorkflowSelectionService selectionService;

    /**
     * Exposes the shared-template contract: which base URL the form actions
     * post to and how the workflow kind is labelled in headings and help
     * texts.
     */
    static void addTemplateAttributes(Model model) {
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
                    "Workflow configuration saved. Please select which workflows are enabled.");
            return "redirect:/system-settings/workflow-configurations/" + saved.getId() + "/workflows";
        } catch (Exception e) {
            log.error("Failed to save workflow configuration", e);
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
            if (log.isDebugEnabled()) {
                log.debug("saveWorkflowSelection id={} selectedKeys={} rawParamKeys={} workflowParams={}",
                        id, selectedWorkflowKeys,
                        allParams == null ? null : allParams.keySet(), workflowParams);
            }
            selectionService.saveSelection(id, selectedWorkflowKeys, workflowParams);
            redirectAttributes.addFlashAttribute("success", "Workflow selection saved successfully");
            return "redirect:/system-settings";
        } catch (Exception e) {
            log.error("Failed to save workflow selection", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save workflow selection: " + e.getMessage());
            return "redirect:/system-settings/workflow-configurations/" + id + "/workflows";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (WorkflowConfigurationKindGuard.requireExpectedKind(id, EXPECTED_KIND, configurationService, redirectAttributes) == null) {
            return "redirect:/system-settings";
        }
        try {
            configurationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Workflow configuration deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete workflow configuration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/system-settings";
    }

    /**
     * Returns the selected workflows for the Bot Details modal (mirrors the
     * MCP / built-in tool endpoints under {@code /bots/...}).
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

