package org.remus.giteabot.admin;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.RepositoryConnection;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryProviderRegistry;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Factory that creates and caches {@link RestClient} and {@link RepositoryConnection}
 * instances from persisted {@link GitIntegration} entities.
 * <p>
 * Connections are cached by integration ID and {@link GitIntegration#getUpdatedAt()}
 * so that configuration changes automatically produce fresh ones. Secrets are only
 * decrypted when a connection's credentials are read, so caching keeps none of them in memory.
 * <p>
 * Provider-specific logic (URL resolution, authentication) is delegated to
 * {@link RepositoryProviderMetadata} implementations via {@link RepositoryProviderRegistry}.
 */
@Slf4j
@Service
public class RepositoryApiClientFactory {

    private final GitIntegrationService gitIntegrationService;
    private final RepositoryProviderRegistry providerRegistry;
    private final Duration userTtl;

    /** Cache key = integrationId, value = (updatedAt-millis, restClient, connection). */
    private final ConcurrentMap<Long, CachedConnection> cache = new ConcurrentHashMap<>();

    public RepositoryApiClientFactory(GitIntegrationService gitIntegrationService,
                                      RepositoryProviderRegistry providerRegistry,
                                      @Value("${giteabot.git-user.cache-ttl:PT5M}") Duration userTtl) {
        this.gitIntegrationService = gitIntegrationService;
        this.providerRegistry = providerRegistry;
        this.userTtl = userTtl;
    }

    /**
     * Returns the {@link RepositoryConnection} for the given Git integration.
     * It is cached and re-created when the integration's updatedAt changes.
     */
    public RepositoryConnection getConnection(GitIntegration integration) {
        return getCachedConnection(integration).connection;
    }

    /** Returns the API client of the integration's {@linkplain #getConnection connection}. */
    public RepositoryApiClient getApiClient(GitIntegration integration) {
        return getConnection(integration).getClient();
    }

    /** Creates an uncached HTTP API client with a supplied plaintext replacement token. */
    public RepositoryApiClient createApiClient(GitIntegration integration, String token) {
        RepositoryProviderMetadata provider = providerRegistry.getProvider(integration.getProviderType());
        RestClient restClient = provider.buildRestClient(integration, token);
        return provider.createConnection(integration, restClient,
                () -> provider.createHttpCredentials(integration, token), null, userTtl).getClient();
    }

    private CachedConnection getCachedConnection(GitIntegration integration) {
        CachedConnection cached = cache.get(integration.getId());
        long updatedMillis = integration.getUpdatedAt().toEpochMilli();

        if (cached != null && cached.updatedAtMillis == updatedMillis) {
            return cached;
        }

        CachedConnection newConnection = buildConnection(integration);
        cache.put(integration.getId(), newConnection);
        log.info("Built new connection for integration '{}' (type={}, url={})",
                integration.getName(), integration.getProviderType(), integration.getUrl());
        return newConnection;
    }

    private CachedConnection buildConnection(GitIntegration integration) {
        RepositoryProviderMetadata provider = providerRegistry.getProvider(integration.getProviderType());
        String decryptedToken = gitIntegrationService.decryptToken(integration);

        log.debug("Building connection for '{}': apiUrl={}, tokenLength={}",
                integration.getName(),
                integration.getUrl(),
                decryptedToken != null ? decryptedToken.length() : 0);

        RestClient restClient = provider.buildRestClient(integration, decryptedToken);
        Supplier<HttpCredentials> httpCredentials = () -> provider.createHttpCredentials(
                integration, gitIntegrationService.decryptToken(integration));
        Supplier<SshCredentials> sshCredentials = integration.getTransport() == GitTransport.SSH
                ? () -> new SshCredentials(gitIntegrationService.decryptSshPrivateKey(integration),
                        integration.getSshKnownHosts())
                : null;
        RepositoryConnection connection = provider.createConnection(
                integration, restClient, httpCredentials, sshCredentials, userTtl);

        return new CachedConnection(integration.getUpdatedAt().toEpochMilli(), restClient, connection);
    }

    private record CachedConnection(long updatedAtMillis, RestClient restClient, RepositoryConnection connection) {}
}
