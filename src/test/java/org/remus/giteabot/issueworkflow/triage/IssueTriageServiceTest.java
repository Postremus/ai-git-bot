package org.remus.giteabot.issueworkflow.triage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.StopReason;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link IssueTriageService}: prompt assembly per mode,
 * untrusted-output validation, and the comment/assign execution paths.
 */
@ExtendWith(MockitoExtension.class)
class IssueTriageServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> PARAMS = Map.of(
            "systemPrompt", "ROUTING PROMPT",
            "assignees", "Alice,Bob,claude-bot,issue_hemingway");

    @Mock
    private AiClientFactory aiClientFactory;
    @Mock
    private GiteaClientFactory giteaClientFactory;
    @Mock
    private AiClient aiClient;
    @Mock
    private RepositoryApiClient repoClient;

    private IssueTriageService service;
    private Bot bot;
    private WebhookPayload payload;

    @BeforeEach
    void setUp() {
        service = new IssueTriageService(aiClientFactory, giteaClientFactory);
        lenient().when(aiClientFactory.getClient(any())).thenReturn(aiClient);
        lenient().when(giteaClientFactory.getApiClient(any())).thenReturn(repoClient);

        bot = new Bot();
        bot.setName("Triage Bot");
        bot.setUsername("triage-bot");

        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("owner");
        WebhookPayload.Repository repository = new WebhookPayload.Repository();
        repository.setName("repo");
        repository.setOwner(owner);
        WebhookPayload.Issue issue = new WebhookPayload.Issue();
        issue.setNumber(42L);
        issue.setTitle("Button color is wrong");
        issue.setBody("The submit button should be blue.");
        payload = new WebhookPayload();
        payload.setRepository(repository);
        payload.setIssue(issue);
    }

    // ---- native tool-calling mode ----

    @Test
    void nativeMode_validToolCall_postsReasonThenAssignsCanonicalName() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"alice\",\"reason\":\"Frontend styling fix\"}"));

        service.triage(bot, payload, PARAMS);

        InOrder order = inOrder(repoClient);
        order.verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("Frontend styling fix"));
        order.verify(repoClient).assignIssue("owner", "repo", 42L, "Alice");
    }

    @Test
    void nativeMode_appendsToolCallSuffixAndAdvertisesAssignIssueTool() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"none\",\"reason\":\"Unclear\"}"));

        service.triage(bot, payload, PARAMS);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<ToolDescriptor>> tools = ArgumentCaptor.forClass(List.class);
        verify(aiClient).chatWithTools(anyList(), anyString(), tools.capture(), prompt.capture(),
                isNull(), anyInt());
        assertTrue(prompt.getValue().startsWith("ROUTING PROMPT"));
        assertTrue(prompt.getValue().contains("you MUST call the assign_issue tool"));
        assertEquals(1, tools.getValue().size());
        assertEquals("assign_issue", tools.getValue().getFirst().name());
        String schema = tools.getValue().getFirst().jsonSchema().toString();
        assertTrue(schema.contains("Alice"));
        assertTrue(schema.contains("none"));
    }

    @Test
    void nativeMode_noToolCall_isRejectedWithErrorComment() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(ChatTurn.text("I would assign this to Alice."));

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));

        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("invalid routing decision"));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void nativeMode_multipleToolCalls_areRejected() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        ToolCall call = toolCall("assign_issue", "{\"name\":\"Alice\",\"reason\":\"x\"}");
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(new ChatTurn("", List.of(call, call), StopReason.END_TURN, 0, 0));

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void nativeMode_unknownTool_isRejected() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("delete_repo", "{}"));

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    // ---- JSON-only mode ----

    @Test
    void jsonMode_none_postsReasonWithoutAssigning() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"assignment\":\"none\",\"reason\":\"Cannot determine the domain\"}");

        service.triage(bot, payload, PARAMS);

        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("Cannot determine the domain"));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void jsonMode_appendsJsonSuffixAndParsesProseWrappedJson() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("Sure! Here you go:\n{\"assignment\":\"Bob\",\"reason\":\"Backend API change\"}");

        service.triage(bot, payload, PARAMS);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiClient).chat(anyList(), anyString(), prompt.capture(), isNull(), anyInt());
        assertTrue(prompt.getValue().startsWith("ROUTING PROMPT"));
        assertTrue(prompt.getValue().contains("Output format"));
        assertTrue(prompt.getValue().contains("\"assignment\""));
        verify(repoClient).assignIssue("owner", "repo", 42L, "Bob");
    }

    @Test
    void jsonMode_unparseableResponse_isRejectedWithErrorComment() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("I have no idea what to do here.");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));

        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("invalid routing decision"));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    // ---- validation (shared between modes) ----

    @Test
    void unsupportedAssignment_isRejected() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"assignment\":\"Mallory\",\"reason\":\"Taking over\"}");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void blankReason_isRejected() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"assignment\":\"Alice\",\"reason\":\"  \"}");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void multiLineReason_isRejected() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"assignment\":\"Alice\",\"reason\":\"line one\\nline two\"}");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    @Test
    void selfAssignment_isRejectedToPreventLoops() {
        when(aiClient.supportsNativeTools()).thenReturn(false);
        when(aiClient.chat(anyList(), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn("{\"assignment\":\"triage-bot\",\"reason\":\"I should do it myself\"}");
        Map<String, Object> params = Map.of(
                "systemPrompt", "ROUTING PROMPT", "assignees", "Alice,triage-bot");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, params));
        verify(repoClient, never()).assignIssue(anyString(), anyString(), any(), anyString());
    }

    // ---- execution failure paths ----

    @Test
    void nonAssignableAccount_postsErrorCommentAndFails() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"Alice\",\"reason\":\"Frontend work\"}"));
        doThrow(new IllegalArgumentException("User 'Alice' is not assignable on owner/repo"))
                .when(repoClient).assignIssue("owner", "repo", 42L, "Alice");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));

        // Reason comment stays visible, error comment explains the failure.
        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L), contains("Frontend work"));
        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("could not assign to `Alice`"));
    }

    @Test
    void unsupportedProvider_postsErrorCommentAndFails() {
        when(aiClient.supportsNativeTools()).thenReturn(true);
        when(aiClient.chatWithTools(anyList(), anyString(), anyList(), anyString(), isNull(), anyInt()))
                .thenReturn(toolTurn("assign_issue", "{\"name\":\"Alice\",\"reason\":\"Frontend work\"}"));
        doThrow(new UnsupportedOperationException("not supported"))
                .when(repoClient).assignIssue("owner", "repo", 42L, "Alice");

        assertThrows(TriageRoutingException.class, () -> service.triage(bot, payload, PARAMS));
        verify(repoClient).postIssueComment(eq("owner"), eq("repo"), eq(42L),
                contains("could not assign to `Alice`"));
    }

    @Test
    void missingIssueInPayload_isANoOp() {
        WebhookPayload empty = new WebhookPayload();

        service.triage(bot, empty, PARAMS);

        verifyNoInteractions(aiClient, repoClient);
    }

    private static ChatTurn toolTurn(String toolName, String argsJson) {
        return new ChatTurn("", List.of(toolCall(toolName, argsJson)), StopReason.END_TURN, 0, 0);
    }

    private static ToolCall toolCall(String toolName, String argsJson) {
        JsonNode args;
        try {
            args = JSON.readTree(argsJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new ToolCall("call-1", toolName, args, Map.of());
    }
}
