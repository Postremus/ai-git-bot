package org.remus.giteabot.eventhook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.RepositoryType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventHookPayloadTest {

    // Jackson 3 (Boot 4.1 default) has ISO-8601 java.time support built in - no module needed.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Bot bot() {
        GitIntegration git = new GitIntegration();
        git.setProviderType(RepositoryType.GITEA);
        Bot bot = new Bot();
        bot.setId(3L);
        bot.setName("review-bot");
        bot.setGitIntegration(git);
        return bot;
    }

    @Test
    void of_buildsEnvelopeWithSchemaVersionAndWireValue() throws Exception {
        EventHookPayload payload = EventHookPayload.of("uuid-1",
                EventHookEventType.PR_WORKFLOW_COMPLETED, bot(), "acme", "shop", 42L, null,
                Map.of("runId", 128));

        String json = objectMapper.writeValueAsString(payload);
        JsonNode root = objectMapper.readTree(json);

        assertEquals(1, root.get("schemaVersion").asInt());
        assertEquals("uuid-1", root.get("id").asText());
        assertEquals("prworkflow.completed", root.get("eventType").asText());
        assertTrue(root.hasNonNull("timestamp"));
        assertEquals("BOT", root.get("actor").get("type").asText());
        assertEquals("review-bot", root.get("actor").get("id").asText());
        assertEquals(3, root.get("integration").get("botId").asLong());
        assertEquals("review-bot", root.get("integration").get("botName").asText());
        assertEquals("gitea", root.get("integration").get("platform").asText());
        assertEquals("acme", root.get("repository").get("owner").asText());
        assertEquals("shop", root.get("repository").get("name").asText());
        assertEquals(42, root.get("repository").get("pullRequest").asLong());
        assertTrue(root.get("repository").get("issue").isNull());
        assertEquals(128, root.get("data").get("runId").asInt());
    }

    @Test
    void of_nullData_serializesAsEmptyObject() throws Exception {
        EventHookPayload payload = EventHookPayload.of("uuid-2",
                EventHookEventType.PR_WORKFLOW_STARTED, bot(), "acme", "shop", null, null, null);

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(payload));

        assertTrue(root.get("data").isObject());
        assertEquals(0, root.get("data").size());
        assertTrue(root.get("repository").get("pullRequest").isNull());
    }

    @Test
    void of_botWithoutGitIntegration_platformIsNull() throws Exception {
        Bot bare = new Bot();
        bare.setId(1L);
        bare.setName("bare-bot");

        EventHookPayload payload = EventHookPayload.of("uuid-3",
                EventHookEventType.ISSUE_ASSIGNMENT_STARTED, bare, "acme", "shop", null, 17L, null);

        JsonNode root = objectMapper.readTree(objectMapper.writeValueAsString(payload));

        assertTrue(root.get("integration").get("platform").isNull());
        assertEquals("issueassignment.started", root.get("eventType").asText());
        assertEquals(17, root.get("repository").get("issue").asLong());
    }
}
