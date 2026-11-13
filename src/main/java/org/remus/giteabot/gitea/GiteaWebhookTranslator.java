package org.remus.giteabot.gitea;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.event.model.WebhookPayload;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.CommentId;
import org.remus.giteabot.event.BotIdentity;
import org.remus.giteabot.event.WebhookEvent;
import org.remus.giteabot.event.WebhookEventKind;
import org.remus.giteabot.event.WebhookSignatures;
import org.remus.giteabot.event.WebhookTranslator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Translates Gitea webhooks into provider-neutral {@link WebhookEvent}s.
 * <p>
 * Gitea's field names closely match {@link WebhookPayload}, so the payload translation
 * is mostly a one-to-one copy. The event type comes from the {@code X-Gitea-Event} header,
 * which carries Gitea's coarse event name ({@code HookEventType.Event()} in Gitea's
 * source): several fine-grained types share one name, e.g. {@code issue_assign} arrives
 * as {@code issues}, and comments on issues and pull requests alike as
 * {@code issue_comment}.
 */
@Slf4j
@Component
public class GiteaWebhookTranslator implements WebhookTranslator {

    static final String EVENT_HEADER = "X-Gitea-Event";

    @Override
    public RepositoryType providerType() {
        return RepositoryType.GITEA;
    }

    /** Gitea signs the body: {@code X-Gitea-Signature: <hex hmac>}. */
    @Override
    public boolean verifySignature(String signingSecret, Map<String, String> headers, byte[] rawBody) {
        return WebhookSignatures.matches(WebhookSignatures.hmacSha256Hex(signingSecret, rawBody),
                headers.get("X-Gitea-Signature"));
    }

    @Override
    public WebhookEvent translate(Bot bot, Map<String, String> headers, Map<String, Object> payload) {
        String eventType = headers.get(EVENT_HEADER);
        if (eventType == null) {
            log.warn("Missing {} header for Gitea webhook", EVENT_HEADER);
            return WebhookEvent.ignored("missing " + EVENT_HEADER + " header");
        }

        WebhookPayload webhookPayload = translatePayload(payload);

        log.debug("Gitea {} event: action={}, pullRequest={}, comment={}, issue={}, sender={}",
                eventType,
                payload.get("action"),
                webhookPayload.getPullRequest() != null ? "present" : "null",
                webhookPayload.getComment() != null ? "present" : "null",
                webhookPayload.getIssue() != null ? "present" : "null",
                webhookPayload.getSender() != null ? webhookPayload.getSender().getLogin() : "null");

        BotIdentity identity = BotIdentity.of(bot);
        return switch (eventType) {
            case "issue_comment" -> classifyComment(identity, webhookPayload);
            case "issues" -> classifyIssue(identity, webhookPayload);
            case "pull_request" -> classifyPullRequest(identity, webhookPayload);
            // Submitted reviews: one event name per review type, all with action "reviewed".
            case "pull_request_approved", "pull_request_rejected", "pull_request_comment" ->
                    classifyReview(identity, webhookPayload);
            default -> WebhookEvent.ignored("unhandled Gitea event type '" + eventType + "'");
        };
    }

    // ---- Classification ----

    /**
     * Gitea sends every comment — on an issue or a pull request, top-level or inline — as
     * {@code issue_comment}. Inline comments are the ones carrying a file path; a pull
     * request is signalled by a top-level {@code pull_request} or, on a plain
     * conversation comment, only by the nested {@code issue.pull_request}.
     */
    private WebhookEvent classifyComment(BotIdentity identity, WebhookPayload payload) {
        String action = payload.getAction();
        if (!"created".equals(action)) {
            return WebhookEvent.ignored("comment action '" + action + "' is not 'created'");
        }
        WebhookPayload.Comment comment = payload.getComment();
        if (comment == null) {
            return WebhookEvent.ignored("Gitea comment event without a comment");
        }
        if (comment.getPath() != null && !comment.getPath().isBlank()) {
            if (payload.getPullRequest() == null && payload.getIssue() == null) {
                return WebhookEvent.ignored("Gitea inline comment event without a pull request or issue");
            }
            return identity.event(WebhookEventKind.PR_INLINE_COMMENT, payload);
        }
        if (payload.getIssue() == null) {
            return WebhookEvent.ignored("Gitea comment event without an issue");
        }
        boolean isPrComment = payload.getPullRequest() != null
                || payload.getIssue().getPullRequest() != null;
        if (isPrComment) {
            return identity.event(WebhookEventKind.PR_COMMENT, payload);
        }
        return identity.event(WebhookEventKind.ISSUE_COMMENT, payload);
    }

    private WebhookEvent classifyIssue(BotIdentity identity, WebhookPayload payload) {
        if (payload.getIssue() == null) {
            return WebhookEvent.ignored("Gitea issues event without an issue");
        }
        String action = payload.getAction();
        return switch (action != null ? action : "") {
            // Gitea names no assignee on the event itself, and issue.assignee is only the
            // first of issue.assignees, so the bot is looked for among all of them.
            case "assigned" -> identity.isIssueAssignee(payload)
                    ? identity.event(WebhookEventKind.ISSUE_ASSIGNED, payload)
                    : WebhookEvent.ignored("issue assigned to someone other than the bot");
            case "opened" -> identity.event(WebhookEventKind.ISSUE_OPENED, payload);
            case "reopened" -> identity.event(WebhookEventKind.ISSUE_REOPENED, payload);
            default -> WebhookEvent.ignored("unhandled Gitea issues action '" + action + "'");
        };
    }

    private WebhookEvent classifyPullRequest(BotIdentity identity, WebhookPayload payload) {
        if (payload.getPullRequest() == null) {
            return WebhookEvent.ignored("Gitea pull_request event without a pull request");
        }
        String action = payload.getAction();
        return switch (action != null ? action : "") {
            case "opened" -> identity.event(WebhookEventKind.PR_OPENED, payload);
            case "reopened" -> identity.event(WebhookEventKind.PR_REOPENED, payload);
            case "synchronized" -> identity.event(WebhookEventKind.PR_SYNCHRONIZED, payload);
            case "closed" -> identity.event(WebhookEventKind.PR_CLOSED, payload);
            case "assigned" -> identity.isAssignee(payload)
                    ? identity.event(WebhookEventKind.PR_ASSIGNED, payload)
                    : WebhookEvent.ignored("pull request assigned to someone other than the bot");
            case "review_requested" -> identity.is(payload.getRequestedReviewer())
                    ? identity.event(WebhookEventKind.PR_REVIEWER_ADDED, payload)
                    : WebhookEvent.ignored("review requested from someone other than the bot");
            default -> WebhookEvent.ignored("unhandled Gitea pull_request action '" + action + "'");
        };
    }

    private WebhookEvent classifyReview(BotIdentity identity, WebhookPayload payload) {
        if (!"reviewed".equals(payload.getAction()) || payload.getReview() == null
                || payload.getPullRequest() == null) {
            return WebhookEvent.ignored("Gitea review event without a submitted review");
        }
        return identity.event(WebhookEventKind.PR_REVIEW_SUBMITTED, payload);
    }

    // ---- Gitea → WebhookPayload translation ----

    @SuppressWarnings("unchecked")
    private WebhookPayload translatePayload(Map<String, Object> raw) {
        WebhookPayload payload = new WebhookPayload();
        payload.setAction((String) raw.get("action"));
        payload.setNumber(toLong(raw.get("number")));
        payload.setSender(extractOwner((Map<String, Object>) raw.get("sender")));
        payload.setRepository(extractRepository((Map<String, Object>) raw.get("repository")));
        payload.setPullRequest(extractPullRequest((Map<String, Object>) raw.get("pull_request")));
        payload.setIssue(extractIssue((Map<String, Object>) raw.get("issue")));
        payload.setComment(extractComment((Map<String, Object>) raw.get("comment")));
        payload.setReview(extractReview((Map<String, Object>) raw.get("review")));
        payload.setRequestedReviewer(extractOwner((Map<String, Object>) raw.get("requested_reviewer")));
        return payload;
    }

    private WebhookPayload.Owner extractOwner(Map<String, Object> owner) {
        if (owner == null) return null;
        WebhookPayload.Owner o = new WebhookPayload.Owner();
        String login = (String) owner.get("login");
        // Fallback to username if login is not present
        o.setLogin(login != null ? login : (String) owner.get("username"));
        return o;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Repository extractRepository(Map<String, Object> repo) {
        if (repo == null) return null;
        WebhookPayload.Repository r = new WebhookPayload.Repository();
        r.setId(toLong(repo.get("id")));
        r.setName((String) repo.get("name"));
        r.setFullName((String) repo.get("full_name"));
        r.setOwner(extractOwner((Map<String, Object>) repo.get("owner")));
        return r;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.PullRequest extractPullRequest(Map<String, Object> pr) {
        if (pr == null) return null;
        WebhookPayload.PullRequest p = new WebhookPayload.PullRequest();
        p.setId(toLong(pr.get("id")));
        p.setNumber(toLong(pr.get("number")));
        p.setTitle((String) pr.get("title"));
        p.setBody((String) pr.get("body"));
        p.setState((String) pr.get("state"));
        p.setMerged(pr.get("merged") instanceof Boolean b ? b : null);
        p.setDiffUrl((String) pr.get("diff_url"));
        p.setUser(extractOwner((Map<String, Object>) pr.get("user")));
        p.setAssignee(extractOwner((Map<String, Object>) pr.get("assignee")));
        p.setAssignees(extractOwners((List<Map<String, Object>>) pr.get("assignees")));
        p.setRequestedReviewers(extractOwners((List<Map<String, Object>>) pr.get("requested_reviewers")));

        Map<String, Object> head = (Map<String, Object>) pr.get("head");
        if (head != null) {
            WebhookPayload.Head h = new WebhookPayload.Head();
            h.setRef((String) head.get("ref"));
            h.setSha((String) head.get("sha"));
            p.setHead(h);
        }
        Map<String, Object> base = (Map<String, Object>) pr.get("base");
        if (base != null) {
            WebhookPayload.Head b = new WebhookPayload.Head();
            b.setRef((String) base.get("ref"));
            b.setSha((String) base.get("sha"));
            p.setBase(b);
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Issue extractIssue(Map<String, Object> issue) {
        if (issue == null) return null;
        WebhookPayload.Issue i = new WebhookPayload.Issue();
        i.setNumber(toLong(issue.get("number")));
        i.setTitle((String) issue.get("title"));
        i.setBody((String) issue.get("body"));
        i.setUser(extractOwner((Map<String, Object>) issue.get("user")));
        i.setAssignee(extractOwner((Map<String, Object>) issue.get("assignee")));

        // Check for pull_request link
        if (issue.get("pull_request") != null) {
            WebhookPayload.IssuePullRequest ipr = new WebhookPayload.IssuePullRequest();
            Map<String, Object> prLink = (Map<String, Object>) issue.get("pull_request");
            ipr.setMerged(prLink.get("merged") instanceof Boolean b ? b : null);
            i.setPullRequest(ipr);
        }

        i.setAssignees(extractOwners((List<Map<String, Object>>) issue.get("assignees")));
        return i;
    }

    @SuppressWarnings("unchecked")
    private WebhookPayload.Comment extractComment(Map<String, Object> comment) {
        if (comment == null) return null;
        WebhookPayload.Comment c = new WebhookPayload.Comment();
        c.setId(CommentId.NumericCommentId.of(toLong(comment.get("id"))));
        c.setBody((String) comment.get("body"));
        c.setPath((String) comment.get("path"));
        c.setDiffHunk((String) comment.get("diff_hunk"));
        c.setLine(comment.get("line") instanceof Number n ? n.intValue() : null);
        c.setPullRequestReviewId(toLong(comment.get("pull_request_review_id")));
        c.setUser(extractOwner((Map<String, Object>) comment.get("user")));
        return c;
    }

    private WebhookPayload.Review extractReview(Map<String, Object> review) {
        if (review == null) return null;
        WebhookPayload.Review r = new WebhookPayload.Review();
        r.setId(toLong(review.get("id")));
        r.setType((String) review.get("type"));
        r.setContent((String) review.get("content"));
        return r;
    }

    private List<WebhookPayload.Owner> extractOwners(List<Map<String, Object>> owners) {
        if (owners == null) return null;
        return owners.stream().map(this::extractOwner).toList();
    }

    private Long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return null;
    }
}
