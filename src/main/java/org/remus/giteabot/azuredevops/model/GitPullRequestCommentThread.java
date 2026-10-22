package org.remus.giteabot.azuredevops.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

// https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#gitpullrequestcommentthread
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GitPullRequestCommentThread {
    private Long id;
    private List<Comment> comments;
    private CommentThreadStatus status;
    private CommentThreadContext threadContext;

    // https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#commentthreadstatus
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    public enum CommentThreadStatus {
        UNKNOWN,
        /** Thread status an author has to resolve; counts against the comment-resolution policy. */
        ACTIVE,
        FIXED,
        WONT_FIX,
        /** Thread status for informational comments that should never block completing the PR. */
        CLOSED,
        BY_DESIGN,
        PENDING
    }
}
