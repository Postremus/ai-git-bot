package org.remus.giteabot.repository;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Metadata and factory interface for Git repository provider integrations.
 * Each repository provider (GitHub, Gitea, GitLab, etc.) implements this interface
 * to define its type, authentication, and client creation.
 */
public interface RepositoryProviderMetadata {

    /**
     * Returns the repository provider type this metadata handles.
     */
    RepositoryType getProviderType();

    /**
     * Builds a configured RestClient for this provider.
     *
     * @param integration    the Git integration configuration
     * @param decryptedToken the decrypted access token
     * @return configured RestClient pointing at the API URL
     */
    RestClient buildRestClient(GitIntegration integration, String decryptedToken);

    /**
     * Creates the credentials for Git over HTTP.
     *
     * @param integration    the Git integration configuration
     * @param decryptedToken the decrypted access token
     * @return the HTTP Git username and password
     */
    HttpCredentials createHttpCredentials(GitIntegration integration, String decryptedToken);

    /**
     * Creates the connection to this provider, including its API client.
     *
     * @param integration     the Git integration configuration
     * @param restClient      the configured RestClient
     * @param httpCredentials the credentials for Git over HTTP
     * @param sshCredentials  the credentials for Git over SSH, or {@code null} when SSH is not selected
     * @param userTtl         how long the account the token authenticates as is cached
     * @return the connection
     */
    RepositoryConnection createConnection(GitIntegration integration, RestClient restClient,
                                          Supplier<HttpCredentials> httpCredentials,
                                          @Nullable Supplier<SshCredentials> sshCredentials,
                                          Duration userTtl);
}

