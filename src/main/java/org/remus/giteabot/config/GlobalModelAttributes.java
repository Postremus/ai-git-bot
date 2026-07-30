package org.remus.giteabot.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes attributes that every Thymeleaf page needs regardless of the
 * controller that rendered it. Currently only the application version, which
 * the balloon-help tour uses to scope its "already shown" cookie (a new
 * release re-triggers the tour).
 */
@ControllerAdvice
public class GlobalModelAttributes {

    private static final String FALLBACK_VERSION = "dev";

    private final String appVersion;

    public GlobalModelAttributes(ObjectProvider<BuildProperties> buildProperties) {
        this.appVersion = buildProperties.stream()
                .findFirst()
                .map(BuildProperties::getVersion)
                .orElse(FALLBACK_VERSION);
    }

    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }
}
