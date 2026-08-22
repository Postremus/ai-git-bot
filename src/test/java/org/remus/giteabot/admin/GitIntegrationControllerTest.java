package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.PostReviewAction;
import org.remus.giteabot.repository.RepositoryType;
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

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(GitIntegrationController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class GitIntegrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GitIntegrationService gitIntegrationService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void newForm_showsProviderTypes() throws Exception {
        mockMvc.perform(get("/git-integrations/new").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("git-integrations/form"))
                .andExpect(content().string(containsString("GITEA")))
                .andExpect(content().string(containsString("GITHUB")))
                .andExpect(content().string(containsString("GITLAB")))
                .andExpect(content().string(containsString("BITBUCKET")));
    }

    @Test
    void editForm_showsClearButton() throws Exception {
        GitIntegration existing = new GitIntegration();
        existing.setId(7L);
        existing.setName("Existing");
        existing.setProviderType(RepositoryType.GITEA);
        existing.setUrl("https://gitea.example.com");
        when(gitIntegrationService.findById(7L)).thenReturn(Optional.of(existing));

        mockMvc.perform(get("/git-integrations/7/edit").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("git-integrations/form"))
                .andExpect(content().string(containsString("id=\"clearTokenBtn\"")))
                .andExpect(content().string(containsString("id=\"clearToken\"")));
    }

    @Test
    void save_newIntegrationDelegatesToService() throws Exception {
        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("token", "gitea-token")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"));

        verify(gitIntegrationService).save(argThat(integration ->
                        "My Gitea".equals(integration.getName())
                                && RepositoryType.GITEA.equals(integration.getProviderType())
                                && "https://gitea.example.com".equals(integration.getUrl())
                                && "gitea-token".equals(integration.getToken())
                                && PostReviewAction.NONE.equals(integration.getPostReviewAction())
                ),
                eq(false));
    }

    @Test
    void save_blankTokenForwardsClearFlag() throws Exception {
        mockMvc.perform(post("/git-integrations/save")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("id", "7")
                        .param("name", "My Gitea")
                        .param("providerType", "GITEA")
                        .param("url", "https://gitea.example.com")
                        .param("token", "")
                        .param("clearToken", "true")
                        .param("postReviewAction", "NONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/git-integrations"));

        verify(gitIntegrationService).save(argThat(integration ->
                        "".equals(integration.getToken()) || integration.getToken() == null),
                eq(true));
    }
}
