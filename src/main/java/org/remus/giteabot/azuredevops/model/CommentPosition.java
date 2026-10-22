package org.remus.giteabot.azuredevops.model;

import lombok.AllArgsConstructor;
import lombok.Data;

// https://learn.microsoft.com/en-us/rest/api/azure/devops/git/pull-request-threads/create?view=azure-devops-rest-6.0&tabs=HTTP#commentposition
@Data
@AllArgsConstructor
public class CommentPosition {
    private int line;
    private int offset;
}
