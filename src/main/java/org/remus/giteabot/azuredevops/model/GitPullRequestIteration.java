package org.remus.giteabot.azuredevops.model;

import lombok.Data;

@Data
public class GitPullRequestIteration {
    private final Long id;
    private final String description;
    private final GitCommitRef commonRefCommit;
    private final GitCommitRef sourceRefCommit;
    private final GitCommitRef targetRefCommit;


}
