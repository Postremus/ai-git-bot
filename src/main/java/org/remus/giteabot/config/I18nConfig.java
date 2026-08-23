package org.remus.giteabot.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.List;
import java.util.Locale;

@Configuration
public class I18nConfig {

    public static final String LOCALE_COOKIE = "lang";
    public static final String LOCALE_PARAM = "lang";

    public record LocaleOption(String code, String displayName) {}

    /** Supported UI locales. Display names are the language's native name (constant across locales). */
    public static final List<LocaleOption> SUPPORTED = List.of(
            new LocaleOption("en", "English"),
            new LocaleOption("fr", "Français"),
            new LocaleOption("de", "Deutsch"),
            new LocaleOption("es", "Español"),
            new LocaleOption("pt", "Português"),
            new LocaleOption("ja", "日本語"),
            new LocaleOption("zh_CN", "简体中文"));

    /**
     * Maps a browser {@code Accept-Language} locale to a supported UI locale,
     * or {@link Locale#ENGLISH} when the language is not supported. Simplified
     * Chinese ({@code zh}) is only accepted with the {@code CN} region.
     */
    static Locale supportedLocaleOrEnglish(Locale browser) {
        if (browser == null) {
            return Locale.ENGLISH;
        }
        String lang = browser.getLanguage().toLowerCase(Locale.ROOT);
        if (lang.isEmpty()) {
            return Locale.ENGLISH;
        }
        if ("zh".equals(lang)) {
            return "cn".equals(browser.getCountry().toLowerCase(Locale.ROOT))
                    ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
        }
        return switch (lang) {
            case "en" -> Locale.ENGLISH;
            case "fr" -> Locale.FRENCH;
            case "de" -> Locale.GERMAN;
            case "es" -> new Locale("es");
            case "pt" -> new Locale("pt");
            case "ja" -> Locale.JAPANESE;
            default -> Locale.ENGLISH;
        };
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver(LOCALE_COOKIE);
        // No cookie → browser Accept-Language if supported, otherwise English.
        // (setDefaultLocale would bypass Accept-Language entirely.)
        resolver.setDefaultLocaleFunction(request -> supportedLocaleOrEnglish(request.getLocale()));
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LOCALE_PARAM);
        return interceptor;
    }

    @Bean
    public WebMvcConfigurer localeWebMvcConfigurer(LocaleChangeInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
