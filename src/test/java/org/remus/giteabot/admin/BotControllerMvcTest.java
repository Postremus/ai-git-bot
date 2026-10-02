package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.prworkflow.config.DeploymentTargetService;
import org.remus.giteabot.prworkflow.config.WorkflowConfigurationService;
import org.remus.giteabot.repository.model.GitAuthor;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolConfigurationService;
import org.remus.giteabot.systemsettings.BotToolSelectionService;
import org.remus.giteabot.systemsettings.McpConfigurationService;
import org.remus.giteabot.systemsettings.McpToolSelectionService;
import org.remus.giteabot.systemsettings.SystemPrompt;
import org.remus.giteabot.systemsettings.SystemPromptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the bot edit form through the real MVC/Thymeleaf stack.
 *
 * <p>Regression guard for the operator-reported 404: the issue-assigned
 * workflow selector's Details button must fetch the ISSUE-kind
 * workflow-configuration endpoint
 * ({@code /system-settings/issue-workflow-configurations/{id}/selected-workflows}).
 * It previously reused the PR-kind base URL, and the PR controller answers
 * 404 for an issue configuration (and for ids that do not exist over there).</p>
 *
 * <p>Also covers the Git author fields end to end: form rendering, binding on
 * save, and the error re-render when the save is rejected.</p>
 */
@WebMvcTest(BotController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class BotControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BotService botService;

    @MockitoBean
    private AiIntegrationService aiIntegrationService;

    @MockitoBean
    private GitIntegrationService gitIntegrationService;

    @MockitoBean
    private SystemPromptService systemPromptService;

    @MockitoBean
    private McpConfigurationService mcpConfigurationService;

    @MockitoBean
    private McpToolSelectionService mcpToolSelectionService;

    @MockitoBean
    private BotToolConfigurationService botToolConfigurationService;

    @MockitoBean
    private BotToolSelectionService botToolSelectionService;

    @MockitoBean
    private WorkflowConfigurationService workflowConfigurationService;

    @MockitoBean
    private DeploymentTargetService deploymentTargetService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void newForm_issueWorkflowDetailsButtonTargetsTheIssueKindEndpoint() throws Exception {
        mockMvc.perform(get("/bots/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "issueWorkflowConfigurationBaseUrl = \"\\/system-settings\\/issue-workflow-configurations\\/\"")))
                .andExpect(content().string(containsString(
                        "fetch(issueWorkflowConfigurationBaseUrl + encodeURIComponent(selectedIssueWorkflowConfigId)"
                                + " + '/selected-workflows')")))
                // the PR selector keeps using the PR-kind endpoint
                .andExpect(content().string(containsString(
                        "fetch(workflowConfigurationBaseUrl + encodeURIComponent(selectedWorkflowConfigId)"
                                + " + '/selected-workflows')")));
    }

    @Test
    void newForm_rendersGitAuthorFieldsWithDefaults() throws Exception {
        mockMvc.perform(get("/bots/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "name=\"gitAuthorName\" value=\"" + GitAuthor.DEFAULT_NAME + "\"")))
                .andExpect(content().string(containsString(
                        "name=\"gitAuthorEmail\" value=\"" + GitAuthor.DEFAULT_EMAIL + "\"")));
    }

    @Test
    void save_bindsGitAuthorFieldsOntoTheSavedBot() throws Exception {
        stubSaveLookups();

        mockMvc.perform(saveRequest("Docs Bot", "docs-bot@example.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/bots"));

        ArgumentCaptor<Bot> saved = ArgumentCaptor.forClass(Bot.class);
        verify(botService).save(saved.capture(), eq(false));
        assertThat(saved.getValue().gitAuthor())
                .isEqualTo(new GitAuthor("Docs Bot", "docs-bot@example.com"));
    }

    @Test
    void save_rejectedGitAuthorReRendersTheFormWithErrorAndEnteredValues() throws Exception {
        stubSaveLookups();
        when(botService.save(any(Bot.class), eq(false)))
                .thenThrow(new IllegalArgumentException("Git author name must not be blank"));

        mockMvc.perform(saveRequest(" ", "docs-bot@example.com"))
                .andExpect(status().isOk())
                // The flash prefix is localized; the service message is not.
                .andExpect(content().string(containsString("Git author name must not be blank")))
                .andExpect(content().string(containsString(
                        "name=\"gitAuthorEmail\" value=\"docs-bot@example.com\"")));
    }

    private void stubSaveLookups() {
        when(aiIntegrationService.findById(1L)).thenReturn(Optional.of(new AiIntegration()));
        GitIntegration gitIntegration = new GitIntegration();
        gitIntegration.setId(2L);
        when(gitIntegrationService.findById(2L)).thenReturn(Optional.of(gitIntegration));
        when(systemPromptService.findById(3L)).thenReturn(Optional.of(new SystemPrompt()));
        when(botToolConfigurationService.findById(4L)).thenReturn(Optional.of(new BotToolConfiguration()));
    }

    private static MockHttpServletRequestBuilder saveRequest(String gitAuthorName, String gitAuthorEmail) {
        return post("/bots/save").with(user("admin").roles("ADMIN")).with(csrf())
                .param("name", "docs-bot")
                .param("username", "docs-bot")
                .param("gitAuthorName", gitAuthorName)
                .param("gitAuthorEmail", gitAuthorEmail)
                .param("aiIntegrationId", "1")
                .param("gitIntegrationId", "2")
                .param("systemPromptId", "3")
                .param("toolConfigurationId", "4");
    }
}
