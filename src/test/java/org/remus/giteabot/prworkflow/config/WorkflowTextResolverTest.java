package org.remus.giteabot.prworkflow.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.prworkflow.WorkflowDescriptor;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowTextResolverTest {

    private final WorkflowDescriptor workflow = new WorkflowDescriptor() {
        @Override public String key() { return "review"; }
        @Override public String displayName() { return "PR Review"; }
        @Override public String description() { return "English description"; }
        @Override public WorkflowParamsSchema paramsSchema() {
            return WorkflowParamsSchema.of(new WorkflowParamField(
                    "framework", "Test framework", WorkflowParamField.ParamType.ENUM,
                    false, "a", "English field description",
                    List.of(new WorkflowParamField.EnumOption("a", "Alpha", "Alpha desc"))));
        }
    };

    @BeforeEach
    void setLocale() {
        LocaleContextHolder.setLocaleContext(new SimpleLocaleContext(Locale.ENGLISH));
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private WorkflowTextResolver resolver(String key, String value) {
        StaticMessageSource ms = new StaticMessageSource();
        ms.addMessage(key, Locale.ENGLISH, value);
        return new WorkflowTextResolver(ms);
    }

    @Test
    void displayName_resolvesKey_andFallsBackToEnglish() {
        assertEquals("Revue de PR", resolver("workflow.review.name", "Revue de PR").displayName(workflow));
        assertEquals("PR Review", new WorkflowTextResolver(new StaticMessageSource()).displayName(workflow));
    }

    @Test
    void description_resolvesKey_andFallsBackToEnglish() {
        assertEquals("Description FR", resolver("workflow.review.description", "Description FR").description(workflow));
        assertEquals("English description", new WorkflowTextResolver(new StaticMessageSource()).description(workflow));
    }

    @Test
    void localizedSchema_localizesLabels_preservingIdentifiersAndFallingBack() {
        StaticMessageSource ms = new StaticMessageSource();
        ms.addMessage("workflow.review.param.framework.label", Locale.ENGLISH, "Framework FR");
        ms.addMessage("workflow.review.param.framework.option.a.label", Locale.ENGLISH, "Alpha-FR");
        WorkflowParamField field = new WorkflowTextResolver(ms).localizedSchema(workflow).require("framework");

        assertEquals("framework", field.name(), "field name (JSON key) must stay untouched");
        assertEquals("Framework FR", field.label(), "field label should be localized");
        assertEquals("English field description", field.description(), "missing description key -> English fallback");
        assertEquals("Alpha-FR", field.allowedValues().get(0).label(), "option label should be localized");
        assertEquals("a", field.allowedValues().get(0).key(), "option key must stay untouched");
        assertEquals("Alpha desc", field.allowedValues().get(0).description(), "missing option description -> fallback");
    }
}
