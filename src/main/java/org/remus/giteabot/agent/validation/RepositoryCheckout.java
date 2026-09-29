package org.remus.giteabot.agent.validation;

import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.RepositoryCredentials;

/**
 * The credential-free remote of a repository and how git authenticates against it,
 * resolved from the repository client before any workspace is allocated.
 */
record RepositoryCheckout(String remote, RepositoryCredentials credentials, boolean usesAuthorizationHeader) {

    /**
     * @throws IllegalStateException when the client returns no remote or no credentials
     */
    static RepositoryCheckout resolve(RepositoryApiClient repositoryClient, String owner, String repo) {
        String remote = repositoryClient.getRepositoryRemote(owner, repo);
        RepositoryCredentials credentials = repositoryClient.getCredentials();
        boolean usesAuthorizationHeader = repositoryClient.usesGitAuthorizationHeader();
        if (remote == null || remote.isBlank() || credentials == null) {
            throw new IllegalStateException("Repository client returned incomplete checkout configuration");
        }
        return new RepositoryCheckout(remote, credentials, usesAuthorizationHeader);
    }
}
