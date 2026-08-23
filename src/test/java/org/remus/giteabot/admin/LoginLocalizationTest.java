package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.I18nConfig;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SetupController.class)
@Import({SecurityConfig.class, I18nConfig.class})
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
@ActiveProfiles("test")
class LoginLocalizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminService adminService;

    @MockitoBean
    private AdminUserRepository adminUserRepository;

    @Test
    void login_acceptLanguageGerman_rendersGerman() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(false);
        mockMvc.perform(get("/login").header("Accept-Language", "de"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Melden Sie sich an")));
    }

    @Test
    void login_langParamFrench_setsCookieAndRendersFrench() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(false);
        mockMvc.perform(get("/login").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(cookie().value(I18nConfig.LOCALE_COOKIE, "fr"))
                .andExpect(content().string(containsString("Connectez-vous")));
    }

    @Test
    void login_unsupportedLanguage_rendersEnglish() throws Exception {
        when(adminService.isSetupRequired()).thenReturn(false);
        mockMvc.perform(get("/login").header("Accept-Language", "it"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sign in to manage your bots")));
    }
}
