package org.remus.giteabot.prworkflow.readmesync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.remus.giteabot.agent.validation.TestWorkspaces;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.agent.validation.WorkspaceException;
import org.remus.giteabot.agent.validation.WorkspaceService;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.gitea.model.WebhookPayload;
import org.remus.giteabot.prworkflow.PrWorkflowContext;
import org.remus.giteabot.prworkflow.e2e.SuiteLifecycleMode;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.systemsettings.SystemPrompt;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focuses on head-branch resolution safety: this workflow writes and pushes to
 * the resolved branch, so it must never fall back to the repository default
 * branch when the PR head ref is missing from the webhook payload (notably for
 * {@code issue_comment}-style events behind the {@code @bot regenerate-readme}
 * slash command).
 */
class ReadmeSyncServiceTest {

    private RepositoryApiClient repoClient;
    private WorkspaceService workspaceService;
    private ReadmeSyncAgent agent;
    private ReadmeSyncService service;

    @BeforeEach
    void setUp() {
        repoClient = mock(RepositoryApiClient.class);
        workspaceService = mock(WorkspaceService.class);
        agent = mock(ReadmeSyncAgent.class);
        AiClient aiClient = mock(AiClient.class);
        SystemPrompt systemPrompt = new SystemPrompt();
        service = new ReadmeSyncService(repoClient, aiClient, systemPrompt, workspaceService, agent);

        when(repoClient.getPullRequestDiff(anyString(), anyString(), anyLong()))
                .thenReturn("diff --git a/x b/x\n+change");
    }

    private ReadmeSyncService.Request request(WebhookPayload payload, SuiteLifecycleMode mode) {
        PrWorkflowContext ctx = new PrWorkflowContext(new org.remus.giteabot.admin.Bot(),
                payload, 1L, (n, l) -> { }, () -> false);
        return new ReadmeSyncService.Request(ctx, List.of("README.md"), 12, mode, null);
    }

    /** issue_comment-style payload: repo + PR number present, but no head ref. */
    private WebhookPayload payloadWithoutHeadRef() {
        WebhookPayload payload = new WebhookPayload();
        WebhookPayload.Repository repo = new WebhookPayload.Repository();
        repo.setName("my-repo");
        WebhookPayload.Owner owner = new WebhookPayload.Owner();
        owner.setLogin("acme");
        repo.setOwner(owner);
        payload.setRepository(repo);
        WebhookPayload.PullRequest pr = new WebhookPayload.PullRequest();
        pr.setNumber(42L);
        payload.setPullRequest(pr); // no head block
        return payload;
    }

    @Test
    void missingHeadRef_andApiCannotResolve_skipsWithoutCommitting() {
        when(repoClient.getPullRequestDetails("acme", "my-repo", 42L)).thenReturn(Map.of());

        ReadmeSyncService.Result result = service.run(
                request(payloadWithoutHeadRef(), SuiteLifecycleMode.COMMIT_TO_PR));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.SKIPPED);
        // The critical guarantee: no clone and no push to any (default) branch.
        verify(workspaceService, never()).openWorkspace(
                any(RepositoryApiClient.class), anyString(), anyString(), anyString(), anyLong());
        verify(workspaceService, never()).openWritablePullRequestWorkspace(
                any(RepositoryApiClient.class), anyString(), anyString(), anyString(), anyLong());
        // And it must never substitute the default branch.
        verify(repoClient, never()).getDefaultBranch(anyString(), anyString());
    }

    @Test
    void missingHeadRef_butApiResolvesIt_clonesThatBranch() {
        when(repoClient.getPullRequestDetails("acme", "my-repo", 42L))
                .thenReturn(Map.of("head", Map.of("ref", "feature/login")));
        // Fail the workspace prep so the run stops right after resolution — we only
        // assert which branch it tried to clone.
        when(workspaceService.openWritablePullRequestWorkspace(
                eq(repoClient), anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new WorkspaceException("stop here"));

        service.run(request(payloadWithoutHeadRef(), SuiteLifecycleMode.COMMIT_TO_PR));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(workspaceService).openWritablePullRequestWorkspace(
                eq(repoClient), eq("acme"), eq("my-repo"), branch.capture(), eq(42L));
        assertThat(branch.getValue()).isEqualTo("feature/login");
        verify(repoClient, never()).getDefaultBranch(anyString(), anyString());
    }

    @Test
    void headRefInPayload_isUsedWithoutApiCall() {
        WebhookPayload payload = payloadWithoutHeadRef();
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature/from-payload");
        payload.getPullRequest().setHead(head);
        when(workspaceService.openWritablePullRequestWorkspace(
                eq(repoClient), anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new WorkspaceException("stop here"));

        service.run(request(payload, SuiteLifecycleMode.COMMIT_TO_PR));

        ArgumentCaptor<String> branch = ArgumentCaptor.forClass(String.class);
        verify(workspaceService).openWritablePullRequestWorkspace(
                eq(repoClient), eq("acme"), eq("my-repo"), branch.capture(), eq(42L));
        assertThat(branch.getValue()).isEqualTo("feature/from-payload");
        verify(repoClient, never()).getPullRequestDetails(anyString(), anyString(), anyLong());
    }

    @Test
    void offerAsPr_onAuthoritativeFork_failsBeforePreparingWorkspace() {
        WebhookPayload payload = payloadWithoutHeadRef();
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("main");
        payload.getPullRequest().setHead(head);
        when(workspaceService.isAuthoritativePullRequestFromFork(
                repoClient, "acme", "my-repo", "main", 42L)).thenReturn(true);

        ReadmeSyncService.Result result = service.run(
                request(payload, SuiteLifecycleMode.OFFER_AS_PR));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.FAILED);
        verify(workspaceService, never()).openWorkspace(any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void offerAsPr_followUpCreationFailure_isWorkflowFailure(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "before");
        WebhookPayload payload = payloadWithoutHeadRef();
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature/docs");
        payload.getPullRequest().setHead(head);
        Workspace ws = TestWorkspaces.at(workspace);
        when(workspaceService.openWorkspace(
                repoClient, "acme", "my-repo", "feature/docs", 42L))
                .thenReturn(ws);
        when(agent.write(any(), any(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    ReadmeSyncToolContext toolContext = invocation.getArgument(1);
                    Files.writeString(workspace.resolve("README.md"), "after");
                    toolContext.recordUpdated("README.md");
                    return new ReadmeSyncAgent.Result(1, "updated", false);
                });
        when(ws.listChangedFiles()).thenReturn(List.of("README.md"));
        when(repoClient.createPullRequest(eq("acme"), eq("my-repo"), anyString(), anyString(),
                anyString(), eq("feature/docs"))).thenReturn(null);

        ReadmeSyncService.Result result = service.run(
                request(payload, SuiteLifecycleMode.OFFER_AS_PR));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.FAILED);
        assertThat(result.summary()).contains("follow-up PR creation failed");
    }

    @Test
    void run_gitStatusFailureDuringScopeCheckCommitsNothing(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "before");
        WebhookPayload payload = payloadWithoutHeadRef();
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setRef("feature/docs");
        payload.getPullRequest().setHead(head);
        Workspace ws = TestWorkspaces.at(workspace);
        when(workspaceService.openWorkspace(repoClient, "acme", "my-repo", "feature/docs", 42L)).thenReturn(ws);
        when(agent.write(any(), any(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    ReadmeSyncToolContext toolContext = invocation.getArgument(1);
                    Files.writeString(workspace.resolve("README.md"), "after");
                    toolContext.recordUpdated("README.md");
                    return new ReadmeSyncAgent.Result(1, "updated", false);
                });
        when(ws.listChangedFiles()).thenThrow(new WorkspaceException("git status failed: fatal"));

        ReadmeSyncService.Result result = service.run(request(payload, SuiteLifecycleMode.OFFER_AS_PR));

        assertThat(result.status()).isEqualTo(ReadmeSyncService.Result.Status.FAILED);
        verify(ws, never()).commitAndPush(any(), any(), any(), any(), anyBoolean());
        verify(ws).close();
    }
}
