package org.remus.giteabot.web;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.util.DetailedException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormErrorsTest {

    private final FormErrors formErrors = new FormErrors(messageSource());

    private static StaticMessageSource messageSource() {
        StaticMessageSource messageSource = new StaticMessageSource();
        messageSource.addMessage("flash.saveFailed", LocaleContextHolder.getLocale(), "Failed to save: {0}");
        messageSource.addMessage("flash.validationFailed", LocaleContextHolder.getLocale(), "Validation failed:");
        messageSource.addMessage("field.invalid", LocaleContextHolder.getLocale(), "Field ''{0}'': {1}");
        messageSource.addMessage("value.tooLong", LocaleContextHolder.getLocale(), "longer than {0} characters");
        return messageSource;
    }

    @Test
    void addTo_plainException_usesFallbackWithExceptionMessage() {
        ConcurrentModel model = new ConcurrentModel();

        formErrors.addTo(model, "flash.saveFailed", new IllegalArgumentException("Name is required"));

        assertEquals("Failed to save: Name is required", model.getAttribute("error"));
        assertFalse(model.containsAttribute("errorDetails"));
    }

    @Test
    void addTo_detailedException_translatesSummaryAndDetails() {
        ConcurrentModel model = new ConcurrentModel();
        MessageSourceResolvable tooLong = resolvable("value.tooLong", "longer than 10 characters", 10);

        formErrors.addTo(model, "flash.saveFailed", new ValidationException("flash.validationFailed", List.of(
                resolvable("field.invalid", "Field 'name': longer than 10 characters", "name", tooLong),
                resolvable("value.tooLong", "longer than 20 characters", 20))));

        assertEquals("Validation failed:", model.getAttribute("error"));
        assertEquals(List.of("Field 'name': longer than 10 characters", "longer than 20 characters"),
                model.getAttribute("errorDetails"));
    }

    @Test
    void addTo_detailedExceptionWithUnknownKeys_fallsBackToDefaultMessages() {
        ConcurrentModel model = new ConcurrentModel();

        formErrors.addTo(model, "flash.saveFailed", new ValidationException("flash.unknown", List.of(
                resolvable("detail.unknown", "first"),
                resolvable("detail.unknown", "second"))));

        assertEquals("first; second", model.getAttribute("error"));
        assertEquals(List.of("first", "second"), model.getAttribute("errorDetails"));
    }

    @Test
    void flashTo_detailedException_setsFlashAttributesOnly() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        formErrors.flashTo(redirectAttributes, "flash.saveFailed", new ValidationException("flash.validationFailed",
                List.of(resolvable("value.tooLong", "longer than 10 characters", 10))));

        assertEquals("Validation failed:", redirectAttributes.getFlashAttributes().get("error"));
        assertEquals(List.of("longer than 10 characters"), redirectAttributes.getFlashAttributes().get("errorDetails"));
        assertTrue(redirectAttributes.isEmpty(), "must not leak into the redirect URL as query parameters");
    }

    private static MessageSourceResolvable resolvable(String code, String defaultMessage, Object... arguments) {
        return new DefaultMessageSourceResolvable(new String[]{code}, arguments, defaultMessage);
    }

    private static class ValidationException extends DetailedException {

        ValidationException(String code, List<MessageSourceResolvable> details) {
            super(code, details);
        }
    }
}
