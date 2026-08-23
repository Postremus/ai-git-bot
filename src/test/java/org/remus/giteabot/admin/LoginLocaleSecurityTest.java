package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.config.I18nConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-context regression guard for the login-page locale selector.
 *
 * <p>The login page's language links navigate to {@code /login?lang=xx}. The
 * {@code lang} query parameter must reach the {@link I18nConfig} locale
 * interceptor, but the form-login permit rule only matched the exact
 * {@code /login} path, so {@code /login?lang=xx} fell through to
 * {@code anyRequest().authenticated()} and was 302-redirected to {@code /login}
 * (dropping the {@code lang} param). The fix permits {@code /login} via
 * {@code requestMatchers("/login")}, which matches the query-string variant.
 *
 * <p>This is a {@link SpringBootTest} (not {@code @WebMvcTest}) because the
 * sliced test's security setup does not reproduce the redirect.
 */
@SpringBootTest
@ActiveProfiles("test")
class LoginLocaleSecurityTest {

    @Autowired private WebApplicationContext wac;
    @Autowired private AdminService adminService;

    @BeforeEach
    void createAdmin() {
        if (adminService.isSetupRequired()) {
            adminService.createAdmin("admin", "password123");
        }
    }

    @Test
    void langParamOnLoginPage_isNotRedirected_andLocalizes() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        // Establish a session first, mirroring a real browser that loads /login
        // before selecting a language from the dropdown.
        mvc.perform(get("/login"));

        mvc.perform(get("/login").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(cookie().value(I18nConfig.LOCALE_COOKIE, "fr"))
                .andExpect(content().string(containsString("Connectez-vous")));
    }
}
