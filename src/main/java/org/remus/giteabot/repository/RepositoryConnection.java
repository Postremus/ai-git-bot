package org.remus.giteabot.repository;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.RepositoryCredentials;
import org.remus.giteabot.repository.model.RepositoryUser;
import org.remus.giteabot.repository.model.SshCredentials;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Everything needed to talk to one Git integration: its URLs, the Git remotes and credentials for Git commands,
 * the API client and the account its token authenticates as. Built by {@link RepositoryProviderMetadata#createConnection}.
 * <p>
 * Credentials are suppliers so that secrets are only decrypted when a Git command needs them,
 * rather than being held in memory for as long as the connection is.
 */
public final class RepositoryConnection {

    @Getter
    private final String baseUrl;
    @Getter
    private final String cloneUrl;
    private final RepositoryRemoteResolver remoteResolver;
    private final boolean usesGitAuthorizationHeader;
    private final Supplier<HttpCredentials> httpCredentials;
    private final @Nullable Supplier<SshCredentials> sshCredentials;
    @Getter
    private final RepositoryApiClient client;
    private final CachedRepositoryUser user;

    /**
     * @param baseUrl                    the API base URL (e.g. {@code https://api.github.com})
     * @param cloneUrl                   the credential-free HTTP clone base URL (e.g. {@code https://github.com})
     * @param remoteResolver             resolves the Git remote of a repository of this integration
     * @param usesGitAuthorizationHeader whether Git over HTTP authenticates with a pre-emptive
     *                                   {@code Authorization} header, see {@link #usesGitAuthorizationHeader()}
     * @param httpCredentials            the credentials for Git over HTTP
     * @param sshCredentials             the credentials for Git over SSH, or {@code null} when SSH is not selected
     * @param client                     the API client of this integration
     * @param userTtl                    how long the account the token authenticates as is cached
     */
    public RepositoryConnection(String baseUrl, String cloneUrl,
                                RepositoryRemoteResolver remoteResolver, boolean usesGitAuthorizationHeader,
                                Supplier<HttpCredentials> httpCredentials,
                                @Nullable Supplier<SshCredentials> sshCredentials,
                                RepositoryApiClient client, Duration userTtl) {
        this.baseUrl = baseUrl;
        this.cloneUrl = cloneUrl;
        this.remoteResolver = remoteResolver;
        this.usesGitAuthorizationHeader = usesGitAuthorizationHeader;
        this.httpCredentials = httpCredentials;
        this.sshCredentials = sshCredentials;
        this.client = client;
        this.user = new CachedRepositoryUser(client::getAuthenticatedUser, userTtl, Clock.systemUTC());
    }

    /**
     * Returns the account the token authenticates as, i.e. the bot's identity on the provider.
     *
     * @throws IllegalStateException when the provider cannot be asked and nothing is cached
     */
    public RepositoryUser getUser() {
        return user.get();
    }

    /**
     * Returns the complete, credential-free Git remote of {@code owner/repo}.
     *
     * @throws IllegalStateException when the remote cannot be resolved
     */
    public String getRepositoryRemote(String owner, String repo) {
        return remoteResolver.resolve(owner, repo);
    }

    /**
     * Returns whether HTTP Git operations must authenticate with a pre-emptive
     * {@code Authorization: Basic} header instead of a credential helper.
     * Needed for providers (e.g. Azure DevOps) whose remotes reject the
     * credential-store challenge/response flow.
     */
    public boolean usesGitAuthorizationHeader() {
        return usesGitAuthorizationHeader;
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
