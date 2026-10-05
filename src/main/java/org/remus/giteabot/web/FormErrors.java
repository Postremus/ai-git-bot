package org.remus.giteabot.web;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.util.DetailedException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Maps an exception from a form action to the {@code error} and {@code errorDetails} attributes
 * that layout.html renders as the error banner.
 * <p>
 * A {@link DetailedException} shows its own translated summary with one translated bullet per detail. Any other
 * exception shows the given fallback message, which receives the exception message as {0} -
 * e.g. {@code flash.saveFailed=Failed to save: {0}}.
 */
@Component
@RequiredArgsConstructor
public class FormErrors {

    private final MessageSource messageSource;

    /** For a controller that re-renders the form, so the user keeps the entered values. */
    public void addTo(Model model, String fallbackMessageKey, Exception e) {
        apply(model::addAttribute, fallbackMessageKey, e);
    }

    /** For a controller that redirects, e.g. back to a list page. */
    public void flashTo(RedirectAttributes redirectAttributes, String fallbackMessageKey, Exception e) {
        apply(redirectAttributes::addFlashAttribute, fallbackMessageKey, e);
    }

    private void apply(BiConsumer<String, Object> attributes, String fallbackMessageKey, Exception e) {
        Locale locale = LocaleContextHolder.getLocale();
        if (e instanceof DetailedException detailed) {
            attributes.accept("error", messageSource.getMessage(detailed, locale));
            attributes.accept("errorDetails", detailed.getDetails().stream()
                    .map(detail -> messageSource.getMessage(detail, locale))
                    .toList());
        } else {
            attributes.accept("error", messageSource.getMessage(fallbackMessageKey, new Object[]{e.getMessage()}, locale));
        }
    }
}
