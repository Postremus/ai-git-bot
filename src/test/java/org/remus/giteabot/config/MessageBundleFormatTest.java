package org.remus.giteabot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A message with arguments is formatted by {@link MessageFormat}, where a single apostrophe starts a
 * quoted section: {@code Endpoint '{0}' saved} renders as "Endpoint {0} saved", and
 * {@code Échec de l'enregistrement : {0}} drops both the apostrophe and the argument.
 */
class MessageBundleFormatTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d");

    @ParameterizedTest
    @ValueSource(strings = {"messages", "messages_de", "messages_es", "messages_fr", "messages_ja", "messages_pt", "messages_zh_CN"})
    void messagesWithArguments_escapeEveryApostrophe(String bundle) throws IOException {
        Properties messages = load(bundle);

        List<String> unescaped = messages.stringPropertyNames().stream()
                .filter(key -> PLACEHOLDER.matcher(messages.getProperty(key)).find())
                .filter(key -> messages.getProperty(key).replace("''", "").contains("'"))
                .sorted()
                .toList();

        assertTrue(unescaped.isEmpty(), bundle + ": write ' as '' in messages with arguments: " + unescaped);
    }

    @Test
    void escapedApostrophes_renderWithTheirArgument() throws IOException {
        String saveFailed = load("messages_fr").getProperty("flash.saveFailed");

        assertEquals("Échec de l'enregistrement : boom", new MessageFormat(saveFailed, Locale.FRENCH).format(new Object[]{"boom"}));
    }

    private static Properties load(String bundle) throws IOException {
        try (InputStream in = MessageBundleFormatTest.class.getResourceAsStream("/" + bundle + ".properties")) {
            assertNotNull(in, bundle + ".properties not found");
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
