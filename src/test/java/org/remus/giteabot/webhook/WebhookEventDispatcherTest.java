package org.remus.giteabot.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotWebhookService;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.model.WebhookPayload;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * Tests the rules of {@link WebhookEventDispatcher} that no single provider exercises. The
 * trigger rules themselves (run-on-create, run-on-update, mention gating, review-again) are
 * covered per provider by the {@code *WebhookTranslatorTest}s, which run through the real
 * dispatcher.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEventDispatcherTest {

    private static final String BOT_ALIAS = "@claude_bot";

    @Mock
    private BotWebhookService botWebhookService;

    private WebhookEventDispatcher dispatcher;
    private Bot bot;
    private WebhookPayload payload;

    @BeforeEach
    void setUp() {
        dispatcher = new WebhookEventDispatcher(botWebhookService, new ReviewTriggerDebouncer());
        lenient().when(botWebhookService.isCallerAllowed(any(), any())).thenReturn(true);
        bot = new Bot();
        bot.setName("test-bot");
        payload = new WebhookPayload();
        WebhookPayload.PullRequest pullRequest = new WebhookPayload.PullRequest();
        pullRequest.setNumber(42L);
        WebhookPayload.Head head = new WebhookPayload.Head();
        head.setSha("abc123");
        pullRequest.setHead(head);
        payload.setPullRequest(pullRequest);
    }

    private static WebhookPayload.Owner sender(String login) {
        WebhookPayload.Owner sender = new WebhookPayload.Owner();
        sender.setLogin(login);
        return sender;
    }

    private WebhookEvent event(WebhookEventKind kind) {
        return eventBuilder(kind).build();
    }

    private WebhookEvent.WebhookEventBuilder eventBuilder(WebhookEventKind kind) {
        return WebhookEvent.builder().kind(kind).payload(payload).botAlias(BOT_ALIAS);
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, mode = EnumSource.Mode.EXCLUDE, names = "IGNORED")
    void eventFromBot_isIgnoredForEveryKind(WebhookEventKind kind) {
        bot.setRunOnPrCreation(true);
        bot.setRunOnPrUpdate(true);
        bot.setRunOnIssueCreation(true);

        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(kind).fromBot(true).botMentioned(true).botIsReviewer(true).build());

        assertEquals("ignored", response.getBody());
        verifyNoInteractions(botWebhookService);
    }

    @Test
    void nonIgnoredEventWithoutPayload_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebhookEvent.builder().kind(WebhookEventKind.PR_OPENED).build());
    }

    @Test
    void automaticTriggersForTheSameCommit_reviewOnce() {
        dispatcher.dispatch(bot, eventBuilder(WebhookEventKind.PR_OPENED).botIsAssignee(true).build());
        ResponseEntity<String> assigned = dispatcher.dispatch(bot, event(WebhookEventKind.PR_ASSIGNED));
        ResponseEntity<String> reviewerAdded = dispatcher.dispatch(bot, event(WebhookEventKind.PR_REVIEWER_ADDED));

        assertEquals("ignored", assigned.getBody());
        assertEquals("ignored", reviewerAdded.getBody());
        verify(botWebhookService, times(1)).reviewPullRequest(eq(bot), any());
    }

    @Test
    void triggerFromACallerOutsideTheWhitelist_doesNotClaimTheCommit() {
        // A contributor who is not allowed opens the pull request; a maintainer who is
        // then adds the bot as reviewer within the debounce window.
        WebhookPayload fromContributor = payload;
        fromContributor.setSender(sender("contributor"));
        WebhookPayload fromMaintainer = new WebhookPayload();
        fromMaintainer.setPullRequest(payload.getPullRequest());
        fromMaintainer.setSender(sender("maintainer"));
        bot.setRunOnPrCreation(true);
        when(botWebhookService.isCallerAllowed(bot, fromContributor)).thenReturn(false);

        ResponseEntity<String> opened = dispatcher.dispatch(bot, event(WebhookEventKind.PR_OPENED));
        ResponseEntity<String> reviewerAdded = dispatcher.dispatch(bot, WebhookEvent.builder()
                .kind(WebhookEventKind.PR_REVIEWER_ADDED).payload(fromMaintainer).botAlias(BOT_ALIAS).build());

        assertEquals("ignored", opened.getBody());
        assertEquals("review triggered", reviewerAdded.getBody());
        verify(botWebhookService, never()).reviewPullRequest(bot, fromContributor);
        verify(botWebhookService).reviewPullRequest(bot, fromMaintainer);
    }

    @ParameterizedTest
    @EnumSource(value = WebhookEventKind.class, names = {"PR_COMMENT", "PR_INLINE_COMMENT"})
    void pullRequestCommentWithoutPullRequestOrIssue_isIgnored(WebhookEventKind kind) {
        payload.setPullRequest(null);

        ResponseEntity<String> response = dispatcher.dispatch(bot, eventBuilder(kind).botMentioned(true).build());

        assertEquals("ignored", response.getBody());
        verifyNoInteractions(botWebhookService);
    }

    @Test
    void reviewAgainComment_isNotDebounced() {
        when(botWebhookService.isReviewAgainRequest(any(), eq(BOT_ALIAS))).thenReturn(true);
        when(botWebhookService.isPullRequestAuthor(any())).thenReturn(true);

        dispatcher.dispatch(bot, event(WebhookEventKind.PR_ASSIGNED));
        ResponseEntity<String> response = dispatcher.dispatch(bot,
                eventBuilder(WebhookEventKind.PR_COMMENT).botMentioned(true).build());

        assertEquals("review triggered", response.getBody());
        verify(botWebhookService, times(2)).reviewPullRequest(eq(bot), any());
    }

    @Test
    void pullRequestComment_reachesTheHandlerWithTheIssueAdded() {
        // GitLab, Bitbucket and Azure DevOps send only the pull request.
        payload.getPullRequest().setMerged(true);

        dispatcher.dispatch(bot, eventBuilder(WebhookEventKind.PR_INLINE_COMMENT).botMentioned(true).build());

        ArgumentCaptor<WebhookPayload> captor = ArgumentCaptor.forClass(WebhookPayload.class);
        verify(botWebhookService).handleInlineComment(eq(bot), captor.capture());
        assertEquals(42L, captor.getValue().getIssue().getNumber());
        assertNotNull(captor.getValue().getIssue().getPullRequest());
        assertEquals(true, captor.getValue().getIssue().getPullRequest().getMerged());
    }
}
