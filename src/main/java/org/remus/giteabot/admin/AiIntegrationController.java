package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Slf4j
@Controller
@RequestMapping("/ai-integrations")
@RequiredArgsConstructor
public class AiIntegrationController {

    private final AiIntegrationService aiIntegrationService;
    private final AiProviderRegistry providerRegistry;

    @GetMapping
    public String list(Model model) {
        List<AiIntegration> integrations = aiIntegrationService.findAll();
        model.addAttribute("integrations", integrations);
        model.addAttribute("activeNav", "ai-integrations");
        return "ai-integrations/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("integration", new AiIntegration());
        addProviderMetadataToModel(model);
        model.addAttribute("activeNav", "ai-integrations");
        return "ai-integrations/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        return aiIntegrationService.findById(id)
                .map(integration -> {
                    model.addAttribute("integration", integration);
                    addProviderMetadataToModel(model);
                    model.addAttribute("activeNav", "ai-integrations");
                    return "ai-integrations/form";
                })
                .orElseGet(() -> {
                    redirectAttributes.addFlashAttribute("error", "AI Integration not found");
                    return "redirect:/ai-integrations";
                });
    }

    private void addProviderMetadataToModel(Model model) {
        model.addAttribute("providerTypes", providerRegistry.getProviderTypes());
        model.addAttribute("providerDisplayNames", providerRegistry.getDisplayNames());
        model.addAttribute("defaultApiUrls", providerRegistry.getDefaultApiUrls());
        model.addAttribute("suggestedModels", providerRegistry.getSuggestedModels());
        model.addAttribute("apiKeyRequirements", providerRegistry.getApiKeyRequirements());
    }

    @PostMapping("/save")
    public String save(@ModelAttribute AiIntegration integration,
                       @RequestParam(required = false) String apiKey,
                       @RequestParam(required = false, defaultValue = "false") boolean clearApiKey,
                       RedirectAttributes redirectAttributes) {
        try {
            // The key form field is a one-way write: only override when a new
            // key is provided. Blank means "keep the stored key" and the
            // explicit Clear button requests removal - both resolved in the
            // service so the kept ciphertext is never re-encrypted.
            if (apiKey != null && !apiKey.isBlank()) {
                integration.setApiKey(apiKey);
            }
            aiIntegrationService.save(integration, clearApiKey);
            redirectAttributes.addFlashAttribute("success", "AI Integration saved successfully");
        } catch (Exception e) {
            log.error("Failed to save AI Integration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to save: " + e.getMessage());
        }
        return "redirect:/ai-integrations";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            aiIntegrationService.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "AI Integration deleted successfully");
        } catch (Exception e) {
            log.error("Failed to delete AI Integration", e);
            redirectAttributes.addFlashAttribute("error", "Failed to delete: " + e.getMessage());
        }
        return "redirect:/ai-integrations";
    }
}
