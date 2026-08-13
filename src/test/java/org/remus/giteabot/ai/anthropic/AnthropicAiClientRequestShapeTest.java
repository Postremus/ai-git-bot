package org.remus.giteabot.ai.anthropic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.remus.giteabot.ai.AiMessage;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the three request-building paths in {@link AnthropicAiClient}
 * actually produce the cache-controllable {@code system} shape (a
 * single-element {@code List<ContentBlock>}), rather than only asserting the
 * shape in isolation as {@link AnthropicRequestTest} does. Complements that
 * DTO-level coverage by exercising the real production call sites.
 */
class AnthropicAiClientRequestShapeTest {

    private RestClient restClient;
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    private RestClient.RequestBodySpec requestBodySpec;
    private RestClient.ResponseSpec responseSpec;
    private AnthropicAiClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        requestBodySpec = mock(RestClient.RequestBodySpec.class);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("/v1/messages")).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any(Object.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);

        AnthropicResponse stubResponse = new AnthropicResponse();
        stubResponse.setContent(List.of());
        when(responseSpec.body(AnthropicResponse.class)).thenReturn(stubResponse);

        client = new AnthropicAiClient(restClient, "claude-sonnet-4-20250514", 1024, true);
    }

    /** Captures the AnthropicRequest actually passed to RestClient's .body(...). */
    private AnthropicRequest capturedRequest() {
        org.mockito.ArgumentCaptor<AnthropicRequest> captor =
                org.mockito.ArgumentCaptor.forClass(AnthropicRequest.class);
        org.mockito.Mockito.verify(requestBodySpec).body(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendReviewRequest_sendsSystemAsSingleTextContentBlock() {
        client.sendReviewRequest("You are a reviewer.", "claude-sonnet-4-20250514",
                1024, "Please review this diff.");

        AnthropicRequest request = capturedRequest();
        assertEquals(1, request.getSystem().size());
        assertEquals("text", request.getSystem().get(0).getType());
        assertEquals("You are a reviewer.", request.getSystem().get(0).getText());

        // legacy user message stays a plain String, unaffected by the system change
        assertInstanceOf(String.class, request.getMessages().get(0).getContent());
        assertEquals("Please review this diff.", request.getMessages().get(0).getContent());
    }

    @Test
    void sendChatRequest_sendsSystemAsSingleTextContentBlock() {
        List<AiMessage> history = List.of(
                AiMessage.builder().role("user").content("Hello").build()
        );

        client.sendChatRequest("You are a chat assistant.", "claude-sonnet-4-20250514",
                1024, history);

        AnthropicRequest request = capturedRequest();
        assertEquals(1, request.getSystem().size());
        assertEquals("text", request.getSystem().get(0).getType());
        assertEquals("You are a chat assistant.", request.getSystem().get(0).getText());
    }

    @Test
    void chatWithTools_sendsSystemAsSingleTextContentBlockAlongsideTools() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        
        List<org.remus.giteabot.ai.ToolDescriptor> tools = List.of(
            new org.remus.giteabot.ai.ToolDescriptor("test_tool", "a test tool", schema)
        );

        client.chatWithTools(List.of(), "Do the thing.", tools,
                "You are an agent.", null, null);

        AnthropicRequest request = capturedRequest();
        assertEquals(1, request.getSystem().size());
        assertEquals("text", request.getSystem().get(0).getType());
        assertEquals("You are an agent.", request.getSystem().get(0).getText());
        assertEquals(1, request.getTools().size());
    }
}