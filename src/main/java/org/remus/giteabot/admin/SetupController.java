package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

@Slf4j
@Controller
@ConditionalOnProperty(prefix = "giteabot.security", name = "login-method", havingValue = "native", matchIfMissing = true)
public class SetupController {

    private final AdminService adminService;
    private final MessageSource messageSource;

    public SetupController(AdminService adminService, MessageSource messageSource) {
        this.adminService = adminService;
        this.messageSource = messageSource;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        if (!adminService.isSetupRequired()) {
            return "redirect:/login";
        }
        return "setup";
    }

    @PostMapping("/setup")
    public String createAdmin(@RequestParam String username,
                              @RequestParam String password,
                              @RequestParam String confirmPassword,
                              RedirectAttributes redirectAttributes) {
        if (!adminService.isSetupRequired()) {
            return "redirect:/login";
        }

        if (username == null || username.isBlank()) {
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.usernameRequired", null, LocaleContextHolder.getLocale()));
            return "redirect:/setup";
        }

        if (password == null || password.length() < 8) {
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.passwordTooShort", null, LocaleContextHolder.getLocale()));
            return "redirect:/setup";
        }

        if (!password.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", messageSource.getMessage("flash.passwordsDoNotMatch", null, LocaleContextHolder.getLocale()));
            return "redirect:/setup";
        }

        adminService.createAdmin(username, password);
        log.info("Admin user '{}' created during initial setup", username);
        redirectAttributes.addFlashAttribute("success", messageSource.getMessage("flash.adminCreated", null, LocaleContextHolder.getLocale()));
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String login(Model model) {
        if (adminService.isSetupRequired()) {
            return "redirect:/setup";
        }
        return "login";
    }
}
