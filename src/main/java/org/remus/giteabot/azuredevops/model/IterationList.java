package org.remus.giteabot.azuredevops.model;

import lombok.Data;

import java.util.List;

@Data
public class IterationList {
    private List<GitPullRequestIteration> value;
}
