package org.remus.giteabot.azuredevops.model;

import lombok.Data;

// https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#commentthreadcontext
@Data
public class CommentThreadContext {
    private String filePath;
    private CommentPosition leftFileStart;
    private CommentPosition leftFileEnd;
    private CommentPosition rightFileStart;
    private CommentPosition rightFileEnd;
}
