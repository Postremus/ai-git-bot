package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationService;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolConfigurationService;
import org.remus.giteabot.systemsettings.BotToolSelectionRow;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpConfiguration;
import org.remus.giteabot.systemsettings.McpConfigurationService;
import org.remus.giteabot.systemsettings.McpToolSelectionRow;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.remus.giteabot.systemsettings.SystemPromptService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotControllerTest {

    private BotController newController(BotService botService,
                                        McpConfigurationService mcpConfigurationService,
                                        McpToolSelectionService mcpToolSelectionService,
                                        BotToolConfigurationService botToolConfigurationService,
                                        BotToolSelectionService botToolSelectionService) {
        return new BotController(
                botService,
                mock(AiIntegrationService.class),
                mock(GitIntegrationService.class),
                mock(SystemPromptService.class),
                mcpConfigurationService,
                mcpToolSelectionService,
                botToolConfigurationService,
                botToolSelectionService,
                mock(WorkflowConfigurationService.class),
                mock(org.remus.giteabot.prworkflow.config.DeploymentTargetService.class));
    }

    @Test
    void newForm_withoutIntegrations_setsMissingFlags() {
        BotController controller = newController(mock(BotService.class), mock(McpConfigurationService.class),
                mock(McpToolSelectionService.class), mock(BotToolConfigurationService.class),
                mock(BotToolSelectionService.class));
        // Unstubbed Mockito mocks return empty lists for findAll()

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        String view = controller.newForm(model);

        assertEquals("bots/form", view);
        assertEquals(Boolean.TRUE, model.getAttribute("missingAiIntegration"));
        assertEquals(Boolean.TRUE, model.getAttribute("missingGitIntegration"));
    }

    @Test
    void newForm_withIntegrations_clearsMissingFlags() {
        AiIntegrationService aiIntegrationService = mock(AiIntegrationService.class);
        GitIntegrationService gitIntegrationService = mock(GitIntegrationService.class);
        when(aiIntegrationService.findAll()).thenReturn(List.of(new AiIntegration()));
        when(gitIntegrationService.findAll()).thenReturn(List.of(new GitIntegration()));
        BotController controller = new BotController(
                mock(BotService.class),
                aiIntegrationService,
                gitIntegrationService,
                mock(SystemPromptService.class),
                mock(McpConfigurationService.class),
                mock(McpToolSelectionService.class),
                mock(BotToolConfigurationService.class),
                mock(BotToolSelectionService.class),
                mock(WorkflowConfigurationService.class),
                mock(org.remus.giteabot.prworkflow.config.DeploymentTargetService.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        String view = controller.newForm(model);

        assertEquals("bots/form", view);
        assertEquals(Boolean.FALSE, model.getAttribute("missingAiIntegration"));
        assertEquals(Boolean.FALSE, model.getAttribute("missingGitIntegration"));
    }

    @Test
    void editForm_existingBot_defaultsMissingFlagsToFalse() {
        BotService botService = mock(BotService.class);
        Bot bot = new Bot();
        bot.setId(1L);
        when(botService.findById(1L)).thenReturn(Optional.of(bot));
        BotController controller = newController(botService, mock(McpConfigurationService.class),
                mock(McpToolSelectionService.class), mock(BotToolConfigurationService.class),
                mock(BotToolSelectionService.class));

        org.springframework.ui.Model model = new org.springframework.ui.ExtendedModelMap();
        String view = controller.editForm(1L, model, mock(RedirectAttributes.class));

        assertEquals("bots/form", view);
        assertEquals(Boolean.FALSE, model.getAttribute("missingAiIntegration"));
        assertEquals(Boolean.FALSE, model.getAttribute("missingGitIntegration"));
    }

    @Test
    void selectedMcpTools_missingConfiguration_returnsNotFound() {
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        BotController controller = newController(mock(BotService.class), mcpConfigurationService,
                mcpToolSelectionService, mock(BotToolConfigurationService.class), mock(BotToolSelectionService.class));
        when(mcpConfigurationService.findById(55L)).thenReturn(Optional.empty());

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedMcpTools(55L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void selectedMcpTools_existingConfiguration_returnsRows() {
        McpConfigurationService mcpConfigurationService = mock(McpConfigurationService.class);
        McpToolSelectionService mcpToolSelectionService = mock(McpToolSelectionService.class);
        BotController controller = newController(mock(BotService.class), mcpConfigurationService,
                mcpToolSelectionService, mock(BotToolConfigurationService.class), mock(BotToolSelectionService.class));
        McpConfiguration config = new McpConfiguration();
        config.setId(7L);
        when(mcpConfigurationService.findById(7L)).thenReturn(Optional.of(config));
        when(mcpToolSelectionService.loadSelectedTools(7L)).thenReturn(List.of(
                new McpToolSelectionRow("mcp:github:get_file", "github", "get_file", "Get file", "Read file", true)
        ));

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedMcpTools(7L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("mcp:github:get_file", response.getBody().getFirst().get("qualifiedName"));
    }

    @Test
    void selectedBuiltinTools_missingConfiguration_returnsNotFound() {
        BotToolConfigurationService toolConfigurationService = mock(BotToolConfigurationService.class);
        BotToolSelectionService toolSelectionService = mock(BotToolSelectionService.class);
        BotController controller = newController(mock(BotService.class), mock(McpConfigurationService.class),
                mock(McpToolSelectionService.class), toolConfigurationService, toolSelectionService);
        when(toolConfigurationService.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedBuiltinTools(99L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void selectedBuiltinTools_existingConfiguration_returnsRows() {
        BotToolConfigurationService toolConfigurationService = mock(BotToolConfigurationService.class);
        BotToolSelectionService toolSelectionService = mock(BotToolSelectionService.class);
        BotController controller = newController(mock(BotService.class), mock(McpConfigurationService.class),
                mock(McpToolSelectionService.class), toolConfigurationService, toolSelectionService);
        BotToolConfiguration configuration = new BotToolConfiguration();
        configuration.setId(8L);
        when(toolConfigurationService.findById(8L)).thenReturn(Optional.of(configuration));
        when(toolSelectionService.loadSelectedTools(8L)).thenReturn(List.of(
                new BotToolSelectionRow("mvn", "VALIDATION", "Run Maven", true)
        ));

        ResponseEntity<List<java.util.Map<String, String>>> response = controller.selectedBuiltinTools(8L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, response.getBody().size());
        assertEquals("mvn", response.getBody().getFirst().get("toolName"));
        assertEquals("VALIDATION", response.getBody().getFirst().get("toolKind"));
    }
}