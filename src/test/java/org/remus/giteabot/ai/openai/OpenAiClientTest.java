package org.remus.giteabot.ai.openai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenAiClientTest {

    private OpenAiClient createClient() {
        RestClient restClient = mock(RestClient.class);
        return new OpenAiClient(restClient, "gpt-4o", 1024,true);
    }

    @Test
    void isPromptTooLongError_detectsContextLengthError() {
        OpenAiClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"This model's maximum context length is 128000 tokens.\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_detectsTooManyTokensError() {
        OpenAiClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"too many tokens in the request\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertTrue(client.isPromptTooLongError(ex));
    }

    @Test
    void isPromptTooLongError_ignoresUnrelatedErrors() {
        OpenAiClient client = createClient();

        HttpClientErrorException ex = HttpClientErrorException.BadRequest.create(
                HttpStatusCode.valueOf(400),
                "Bad Request",
                HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"invalid api key\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertFalse(client.isPromptTooLongError(ex));
    }

    @Test
    void supportsNativeTools_defaultsToTrue() {
        OpenAiClient client = createClient();
        assertTrue(client.supportsNativeTools());
    }

    @Test
    void supportsNativeTools_canBeDisabled() {
        OpenAiClient client = new OpenAiClient(mock(RestClient.class), "gpt-4o", 1024,false);
        assertFalse(client.supportsNativeTools());
    }

    @Test
    void extractText_explainsTokenBudget_whenFinishReasonIsLength() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice(null, "length"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("finish_reason=length"));
        assertTrue(result.contains("max tokens"));
        assertFalse(result.contains("empty response from AI"));
    }

    @Test
    void extractText_explainsTokenBudget_whenContentIsBlankAndFinishReasonIsLength() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice("", "length"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("finish_reason=length"));
    }

    @Test
    void extractText_keepsGenericMessage_whenNoLengthFinishReason() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice(null, "stop"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("empty response from AI"));
        assertFalse(result.contains("finish_reason=length"));
    }

    @Test
    void extractText_returnsContent_whenPresent() {
        OpenAiClient client = createClient();
        OpenAiResponse response = responseWith(choice("The review looks good.", "stop"));

        String result = client.extractText(reviewRequest(), response, "review");

        assertTrue(result.contains("The review looks good."));
    }

    private OpenAiRequest reviewRequest() {
        // Only forwarded to usage reporting; extractText never reads it.
        return OpenAiRequest.builder()
                .model("test-model")
                .maxTokens(1024)
                .build();
    }

    private OpenAiResponse responseWith(OpenAiResponse.Choice... choices) {
        OpenAiResponse response = new OpenAiResponse();
        response.setChoices(java.util.Arrays.asList(choices));
        return response;
    }

    private OpenAiResponse.Choice choice(String content, String finishReason) {
        OpenAiResponse.Choice choice = new OpenAiResponse.Choice();
        OpenAiResponse.Message message = new OpenAiResponse.Message();
        message.setContent(content);
        choice.setMessage(message);
        choice.setFinishReason(finishReason);
        return choice;
    }

}
