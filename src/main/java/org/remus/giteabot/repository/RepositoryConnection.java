package org.remus.giteabot.repository;

import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Everything needed to talk to one Git integration: its URLs, the credentials for Git commands,
 * the API client and the account its token authenticates as. Built by {@link RepositoryProviderMetadata#createConnection}.
 * <p>
 * Credentials are suppliers so that secrets are only decrypted when a Git command needs them,
 * rather than being held in memory for as long as the connection is.
 */
public final class RepositoryConnection {

    private final String baseUrl;
    private final String cloneUrl;
    private final Supplier<HttpCredentials> httpCredentials;
    private final @Nullable Supplier<SshCredentials> sshCredentials;
    private final RepositoryApiClient client;
    private final CachedRepositoryUser user;

    /**
     * @param baseUrl         the API base URL (e.g. {@code https://api.github.com})
     * @param cloneUrl        the credential-free HTTP clone base URL (e.g. {@code https://github.com})
     * @param httpCredentials the credentials for Git over HTTP
     * @param sshCredentials  the credentials for Git over SSH, or {@code null} when SSH is not selected
     * @param client          the API client of this integration
     * @param userTtl         how long the account the token authenticates as is cached
     */
    public RepositoryConnection(String baseUrl, String cloneUrl,
                                Supplier<HttpCredentials> httpCredentials,
                                @Nullable Supplier<SshCredentials> sshCredentials,
                                RepositoryApiClient client, Duration userTtl) {
        this.baseUrl = baseUrl;
        this.cloneUrl = cloneUrl;
        this.httpCredentials = httpCredentials;
        this.sshCredentials = sshCredentials;
        this.client = client;
        this.user = new CachedRepositoryUser(client::getAuthenticatedUser, userTtl, Clock.systemUTC());
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getCloneUrl() {
        return cloneUrl;
    }

    public RepositoryApiClient getClient() {
        return client;
    }

    /**
     * Returns the account the token authenticates as, i.e. the bot's identity on the provider.
     *
     * @throws IllegalStateException when the provider cannot be asked and nothing is cached
     */
    public RepositoryUser getUser() {
        return user.get();
    }

    /** Returns the transport Git commands use. */
    public GitTransport getTransport() {
        return sshCredentials != null ? GitTransport.SSH : GitTransport.HTTP;
    }

    /** Returns the credentials of the selected {@linkplain #getTransport() transport}. */
    public RepositoryCredentials getCredentials() {
        return sshCredentials != null ? sshCredentials.get() : httpCredentials.get();
    }
}
