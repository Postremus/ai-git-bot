package org.remus.giteabot.ai.anthropic;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.agent.shared.AgentJackson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicRequestTest {

    private final ObjectMapper mapper = AgentJackson.mapper();

    @Test
    void systemSerializesAsArrayNotString() {
        AnthropicRequest request = AnthropicRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(1024)
                .system(List.of(
                        AnthropicRequest.ContentBlock.builder()
                                .type("text")
                                .text("You are a helpful code reviewer.")
                                .build()
                ))
                .messages(List.of())
                .build();

        JsonNode json = mapper.valueToTree(request);

        assertTrue(json.get("system").isArray(),
                "system must serialize as a JSON array to carry a cache_control breakpoint");
        assertEquals("text", json.get("system").get(0).get("type").asString());
        assertEquals("You are a helpful code reviewer.",
                json.get("system").get(0).get("text").asString());
    }

    @Test
    void cacheControlSerializesOnIntendedBlockOnly() {
        AnthropicRequest.ContentBlock cachedBlock = AnthropicRequest.ContentBlock.builder()
                .type("text")
                .text("cached system prompt")
                .cacheControl(AnthropicRequest.CacheControl.builder().build())
                .build();
        AnthropicRequest.ContentBlock uncachedBlock = AnthropicRequest.ContentBlock.builder()
                .type("text")
                .text("uncached message")
                .build();

        AnthropicRequest request = AnthropicRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(1024)
                .system(List.of(cachedBlock))
                .messages(List.of(
                        AnthropicRequest.Message.builder()
                                .role("user")
                                .content(List.of(uncachedBlock))
                                .build()
                ))
                .build();

        JsonNode json = mapper.valueToTree(request);
        JsonNode systemBlock = json.get("system").get(0);
        JsonNode messageBlock = json.get("messages").get(0).get("content").get(0);

        assertTrue(systemBlock.has("cache_control"),
                "the system block should carry the cache_control breakpoint");
        assertEquals("ephemeral", systemBlock.get("cache_control").get("type").asString());
        assertFalse(messageBlock.has("cache_control"),
                "a block without an explicit breakpoint must not serialize cache_control");
    }

    @Test
    void cacheControlTtlIsOmittedWhenNull() {
        AnthropicRequest.ContentBlock block = AnthropicRequest.ContentBlock.builder()
                .type("text")
                .text("prompt")
                .cacheControl(AnthropicRequest.CacheControl.builder().build())
                .build();

        JsonNode json = mapper.valueToTree(block);

        assertFalse(json.get("cache_control").has("ttl"),
                "a null ttl (default 5-minute cache) must not be serialized, per @JsonInclude(NON_NULL)");
    }

    @Test
    void responseUsageDeserializesCacheTokenFields() {
        String responseJson = """
                {
                  "id": "msg_123",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "hi"}],
                  "model": "claude-sonnet-4-20250514",
                  "stop_reason": "end_turn",
                  "usage": {
                    "input_tokens": 300,
                    "output_tokens": 50,
                    "cache_creation_input_tokens": 1200,
                    "cache_read_input_tokens": 14500
                  }
                }
                """;

        AnthropicResponse response = mapper.readValue(responseJson, AnthropicResponse.class);

        assertEquals(300, response.getUsage().getInputTokens());
        assertEquals(50, response.getUsage().getOutputTokens());
        assertEquals(1200, response.getUsage().getCacheCreationInputTokens());
        assertEquals(14500, response.getUsage().getCacheReadInputTokens());
    }
}