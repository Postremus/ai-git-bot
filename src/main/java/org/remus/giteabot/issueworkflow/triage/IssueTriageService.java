package org.remus.giteabot.issueworkflow.triage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AiClientFactory;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.GiteaClientFactory;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.ChatTurn;
import org.remus.giteabot.ai.ToolCall;
import org.remus.giteabot.ai.ToolDescriptor;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-shot issue triage/routing for {@link TriageIssueWorkflow}: asks the
 * model to pick exactly one assignee from the configured account names,
 * posts the routing reason as an issue comment, and performs the assignment
 * through the provider API.
 *
 * <p>Two output protocols share one validation path:</p>
 * <ul>
 *   <li><b>Tool-calling mode</b> when the bot's AI client supports native
 *   tools ({@link AiClient#supportsNativeTools()}, which already honors the
 *   per-integration legacy-tool-calling switch): the configured prompt gets
 *   the tool-call suffix and the model must emit exactly one
 *   {@code assign_issue} tool call.</li>
 *   <li><b>JSON-only mode</b> otherwise: the prompt gets the JSON suffix and
 *   the response is parsed for a single {@code {"assignment", "reason"}}
 *   object.</li>
 * </ul>
 *
 * <p>Model output is treated as untrusted: the assignment must be one of the
 * configured names (or the reserved {@code none}), the reason must be a
 * non-blank single line, and the model may not route the issue back to the
 * triage bot itself (that would retrigger the workflow in a loop). Any
 * violation, and any provider-side assignability failure, results in an
 * error comment on the issue plus a {@link TriageRoutingException} so the
 * orchestrator records the failure through the existing issue-workflow
 * error handling.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueTriageService {

    /** Reserved assignment value: post the reason but leave the issue unassigned. */
    static final String NONE_ASSIGNEE = "none";

    static final String TOOL_NAME = "assign_issue";

    /** Routing answers are a single tool call / JSON object — keep the budget small. */
    private static final int MAX_TOKENS = 1024;

    /** Appended to the configured prompt when native tool calling is available. */
    static final String TOOL_CALL_SUFFIX = """
            IMPORTANT: After reading the issue, you MUST call the assign_issue tool. Do not output plain text or JSON — always use the tool call.

            The assign_issue tool has these parameters:
            - name: The assignment. Must be one of the known users
            - reason: A one-line justification for the assignment

            Output ONLY the tool call. No explanation, no commentary.
            """;

    /** Appended to the configured prompt when native tool calling is not available. */
    static final String JSON_SUFFIX = """
            Output format — for every issue, respond with ONLY this JSON object:

            {
              "assignment": "<user>",
              "reason": "<one-line justification>"
            }
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiClientFactory aiClientFactory;
    private final GiteaClientFactory giteaClientFactory;

    /**
     * Runs one triage pass for an issue-created or issue-assigned event.
     * Failures are reported as an issue comment plus a
     * {@link TriageRoutingException}; unexpected runtime exceptions propagate
     * to the orchestrator unchanged.
     */
    public void triage(Bot bot, WebhookPayload payload, Map<String, Object> params) {
        WebhookPayload.Issue issue = payload.getIssue();
        String owner = payload.getRepository() != null && payload.getRepository().getOwner() != null
                ? payload.getRepository().getOwner().getLogin() : null;
        String repo = payload.getRepository() != null ? payload.getRepository().getName() : null;
        if (issue == null || issue.getNumber() == null || owner == null || repo == null) {
            log.warn("[Bot '{}'] Triage skipped — webhook payload lacks issue or repository", bot.getName());
            return;
        }
        if (issue.getPullRequest() != null) {
            log.debug("[Bot '{}'] Triage skipped — issue #{} is a pull request", bot.getName(), issue.getNumber());
            return;
        }

        Set<String> allowed = allowedAssignees(params);
        String systemPrompt = configuredPrompt(params);
        AiClient aiClient = aiClientFactory.getClient(bot.getAiIntegration());
        RepositoryApiClient repoClient = giteaClientFactory.getApiClient(bot.getGitIntegration());
        String userMessage = "Issue #" + issue.getNumber() + ": "
                + nullToEmpty(issue.getTitle()) + "\n\n" + nullToEmpty(issue.getBody());

        RoutingDecision decision;
        try {
            RoutingDecision raw = aiClient.supportsNativeTools()
                    ? routeWithToolCall(aiClient, systemPrompt, userMessage, allowed)
                    : routeWithJson(aiClient, systemPrompt, userMessage);
            decision = validate(raw, allowed, bot);
        } catch (InvalidRoutingOutputException e) {
            postErrorComment(repoClient, owner, repo, issue.getNumber(),
                    "Issue triage failed: the model returned an invalid routing decision ("
                            + e.getMessage() + "). No assignment was made.");
            throw new TriageRoutingException("Issue triage routing failed: " + e.getMessage(), e);
        }

        log.info("[Bot '{}'] Triage routed issue #{} to '{}': {}", bot.getName(), issue.getNumber(),
                decision.assignee(), decision.reason());

        if (NONE_ASSIGNEE.equals(decision.assignee())) {
            repoClient.postIssueComment(owner, repo, issue.getNumber(),
                    "Issue triage: " + decision.reason() + " (no assignment)");
            return;
        }

        // Reason comment first: even when the assignment itself fails, the
        // rationale stays visible on the issue.
        repoClient.postIssueComment(owner, repo, issue.getNumber(),
                "Issue triage: " + decision.reason() + " (routing to `" + decision.assignee() + "`)");
        try {
            repoClient.assignIssue(owner, repo, issue.getNumber(), decision.assignee());
        } catch (UnsupportedOperationException | IllegalArgumentException | RestClientResponseException e) {
            postErrorComment(repoClient, owner, repo, issue.getNumber(),
                    "Issue triage: could not assign to `" + decision.assignee() + "` — the account does"
                            + " not exist, is not assignable on this repository, or the bot lacks the"
                            + " necessary permission. No assignment was made.");
            throw new TriageRoutingException(
                    "Issue triage could not assign to '" + decision.assignee() + "': " + e.getMessage(), e);
        }
    }

    /**
     * The machine-readable allowed set: the configured comma-separated
     * account names plus the reserved {@code none}. Both output modes
     * validate against this one set.
     */
    private Set<String> allowedAssignees(Map<String, Object> params) {
        Object raw = params.get(TriageParam.ASSIGNEES.key());
        String text = raw != null && !String.valueOf(raw).isBlank()
                ? String.valueOf(raw) : TriageIssueWorkflow.DEFAULT_ASSIGNEES;
        Set<String> allowed = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !NONE_ASSIGNEE.equalsIgnoreCase(trimmed)) {
                allowed.add(trimmed);
            }
        }
        allowed.add(NONE_ASSIGNEE);
        return allowed;
    }

    private String configuredPrompt(Map<String, Object> params) {
        Object raw = params.get(TriageParam.SYSTEM_PROMPT.key());
        return raw != null && !String.valueOf(raw).isBlank()
                ? String.valueOf(raw) : TriageIssueWorkflow.DEFAULT_SYSTEM_PROMPT;
    }

    /**
     * Tool-calling mode: one {@code chatWithTools} round advertising only the
     * {@code assign_issue} tool; the model must answer with exactly one call
     * to it.
     */
    private RoutingDecision routeWithToolCall(AiClient aiClient, String systemPrompt,
                                              String userMessage, Set<String> allowed) {
        String prompt = systemPrompt + "\n\n" + TOOL_CALL_SUFFIX;
        ChatTurn turn = aiClient.chatWithTools(List.of(), userMessage,
                List.of(assignIssueTool(allowed)), prompt, null, MAX_TOKENS);
        if (turn == null || !turn.hasToolCalls()) {
            throw new InvalidRoutingOutputException("model returned no tool call");
        }
        if (turn.toolCalls().size() != 1) {
            throw new InvalidRoutingOutputException(
                    "model returned " + turn.toolCalls().size() + " tool calls instead of exactly one");
        }
        ToolCall call = turn.toolCalls().getFirst();
        if (!TOOL_NAME.equals(call.name())) {
            throw new InvalidRoutingOutputException("model called unknown tool '" + call.name() + "'");
        }
        return new RoutingDecision(stringArg(call.args(), "name"), stringArg(call.args(), "reason"));
    }

    /**
     * JSON-only mode: one plain {@code chat} round; the response is parsed
     * for the {@code {"assignment", "reason"}} object (tolerating prose
     * around the first JSON block).
     */
    private RoutingDecision routeWithJson(AiClient aiClient, String systemPrompt, String userMessage) {
        String prompt = systemPrompt + "\n\n" + JSON_SUFFIX;
        String response = aiClient.chat(List.of(), userMessage, prompt, null, MAX_TOKENS);
        JsonNode node = extractJsonObject(response);
        if (node == null) {
            throw new InvalidRoutingOutputException("no parseable JSON object in the model response");
        }
        return new RoutingDecision(stringArg(node, "assignment"), stringArg(node, "reason"));
    }

    /**
     * Validates the untrusted model output: exactly one assignment, from the
     * configured names (canonicalized to the configured casing), with a
     * non-blank single-line reason, and never the triage bot itself.
     */
    private RoutingDecision validate(RoutingDecision raw, Set<String> allowed, Bot bot) {
        if (raw.assignee() == null || raw.assignee().isBlank()) {
            throw new InvalidRoutingOutputException("missing assignment");
        }
        String canonical = allowed.stream()
                .filter(a -> a.equalsIgnoreCase(raw.assignee().trim()))
                .findFirst()
                .orElseThrow(() -> new InvalidRoutingOutputException(
                        "unsupported assignment '" + raw.assignee() + "'"));
        if (bot.getUsername() != null && canonical.equalsIgnoreCase(bot.getUsername())) {
            throw new InvalidRoutingOutputException(
                    "refusing to self-assign to the triage bot '" + bot.getUsername() + "'");
        }
        if (raw.reason() == null || raw.reason().isBlank()) {
            throw new InvalidRoutingOutputException("missing reason");
        }
        String reason = raw.reason().trim();
        if (reason.contains("\n") || reason.contains("\r")) {
            throw new InvalidRoutingOutputException("reason must be a single line");
        }
        return new RoutingDecision(canonical, reason);
    }

    private ToolDescriptor assignIssueTool(Set<String> allowed) {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode name = props.putObject("name");
        name.put("type", "string");
        name.put("description", "The assignment. Must be one of the known users.");
        allowed.forEach(name.putArray("enum")::add);
        ObjectNode reason = props.putObject("reason");
        reason.put("type", "string");
        reason.put("description", "A one-line justification for the assignment.");
        schema.putArray("required").add("name").add("reason");
        return new ToolDescriptor(TOOL_NAME,
                "Assign the issue to one of the known users with a one-line justification.", schema);
    }

    private JsonNode extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(text.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringArg(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Best-effort error comment; a comment failure never masks the routing failure. */
    private void postErrorComment(RepositoryApiClient repoClient, String owner, String repo,
                                  Long issueNumber, String body) {
        try {
            repoClient.postIssueComment(owner, repo, issueNumber, body);
        } catch (Exception e) {
            log.warn("Failed to post triage error comment on issue #{} in {}/{}: {}",
                    issueNumber, owner, repo, e.getMessage());
        }
    }

    /** One validated routing decision: the canonical assignee name plus the one-line reason. */
    private record RoutingDecision(String assignee, String reason) {
    }

    /** Model output that failed parsing or validation. */
    private static final class InvalidRoutingOutputException extends RuntimeException {
        private InvalidRoutingOutputException(String message) {
            super(message);
        }
    }
}
