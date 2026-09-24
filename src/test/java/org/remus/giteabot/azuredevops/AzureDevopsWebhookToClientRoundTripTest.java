package org.remus.giteabot.azuredevops;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.gitea.model.WebhookPayload;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Walks the production path end to end: a realistic Azure DevOps Service Hook payload
 * goes through {@link AzureDevopsWebhookHandler#translatePayload}, then {@code owner} and
 * {@code repo} are pulled out the way every consumer does it —
 * {@code payload.getRepository().getOwner().getLogin()} and
 * {@code payload.getRepository().getName()} — and fed into
 * {@link AzureDevopsAddress#parse(String, String)}, the same call
 * {@code RepositoryApiClient} implementations make on every request.
 * <p>
 * This is the one seam where the two halves of the addressing convention have to agree:
 * {@code repo} must arrive as {@code "Project/Repository"} through {@code getName()}, and
 * the organization must come from the payload rather than {@code GitIntegration.url}. The
 * translation and address-parsing layers are otherwise tested in isolation, where a
 * mismatch between them stays invisible — here it surfaces as an
 * {@link IllegalArgumentException} from {@code parse}.
 */
class AzureDevopsWebhookToClientRoundTripTest {

    private final AzureDevopsWebhookHandler handler = new AzureDevopsWebhookHandler(null, null);

    @Test
    void pullRequestCreated_ownerAndRepoRoundTripToAValidAddress() {
        WebhookPayload payload = handler.translatePayload(
                "git.pullrequest.created", pullRequestCreatedPayload());
        assertNotNull(payload, "translation must succeed for a well-formed payload");

        // Exactly what every real consumer does — see BotWebhookService, CodeReviewService,
        // AgentReviewService, etc. — before calling into RepositoryApiClient.
        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();

        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);

        assertEquals("fabrikam", addr.organization());
        assertEquals("MyProject", addr.project());
        assertEquals("my-service", addr.name());
    }

    @Test
    void pullRequestCommentEvent_ownerAndRepoRoundTripToAValidAddress() {
        WebhookPayload payload = handler.translatePayload(
                "ms.vss-code.git-pullrequest-comment-event", commentPayload());
        assertNotNull(payload, "translation must succeed for a well-formed comment payload");

        String owner = payload.getRepository().getOwner().getLogin();
        String repo = payload.getRepository().getName();

        AzureDevopsAddress addr = AzureDevopsAddress.parse(owner, repo);

        assertEquals("fabrikam", addr.organization());
        assertEquals("MyProject", addr.project());
        assertEquals("my-service", addr.name());
    }

    /**
     * Realistic (trimmed) shape of a {@code git.pullrequest.created} Service Hook body,
     * with "Resource details to send = All" as the setup guide requires.
     */
    private static Map<String, Object> pullRequestCreatedPayload() {
        return Map.of("eventType", "git.pullrequest.created",
                "resource", Map.ofEntries(
                        Map.entry("pullRequestId", 42),
                        Map.entry("title", "Add feature"),
                        Map.entry("description", "does things"),
                        Map.entry("status", "active"),
                        Map.entry("sourceRefName", "refs/heads/feature-branch"),
                        Map.entry("targetRefName", "refs/heads/main"),
                        Map.entry("lastMergeSourceCommit", Map.of("commitId", "head-sha")),
                        Map.entry("lastMergeTargetCommit", Map.of("commitId", "base-sha")),
                        Map.entry("createdBy", Map.of("uniqueName", "dev@fabrikam.com",
                                "displayName", "Dev")),
                        Map.entry("reviewers", List.of(Map.of("uniqueName", "bot@fabrikam.com",
                                "displayName", "Bot"))),
                        Map.entry("repository", Map.of(
                                "id", "11111111-2222-3333-4444-555555555555",
                                "name", "my-service",
                                "url", "https://dev.azure.com/fabrikam/DefaultCollection/_apis/git/"
                                        + "repositories/11111111-2222-3333-4444-555555555555",
                                "project", Map.of("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                        "name", "MyProject")))));
    }

    /**
     * Realistic shape of a {@code ms.vss-code.git-pullrequest-comment-event} body — the
     * repository lives under {@code resource.pullRequest.repository}, not
     * {@code resource.repository}, which is exactly why the organization-resolution
     * source field differs between the two event families.
     */
    private static Map<String, Object> commentPayload() {
        return Map.of("eventType", "ms.vss-code.git-pullrequest-comment-event",
                "resource", Map.of(
                        "comment", Map.of("id", 7,
                                "content", "@bot please review",
                                "commentType", "text",
                                "author", Map.of("uniqueName", "dev@fabrikam.com"),
                                "_links", Map.of("threads", Map.of("href",
                                        "https://dev.azure.com/fabrikam/DefaultCollection/_apis/git/"
                                                + "repositories/11111111-2222-3333-4444-555555555555/"
                                                + "pullRequests/42/threads/5"))),
                        "pullRequest", Map.of(
                                "pullRequestId", 42,
                                "title", "Add feature",
                                "sourceRefName", "refs/heads/feature-branch",
                                "targetRefName", "refs/heads/main",
                                "createdBy", Map.of("uniqueName", "dev@fabrikam.com"),
                                "repository", Map.of(
                                        "id", "11111111-2222-3333-4444-555555555555",
                                        "name", "my-service",
                                        "url", "https://dev.azure.com/fabrikam/DefaultCollection/_apis/git/"
                                                + "repositories/11111111-2222-3333-4444-555555555555",
                                        "project", Map.of("id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                                                "name", "MyProject")))));
    }
}
