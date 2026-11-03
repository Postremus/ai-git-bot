package org.remus.giteabot.repository;

import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.RepositoryUser;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Test helper wrapping an API client, typically a mock, in a {@link RepositoryConnection}. */
public final class RepositoryConnections {

    public static final RepositoryUser USER = new RepositoryUser("1", "bot", "Bot", "bot@example.com");

    private static final String URL = "https://git.example.com";

    private static final Map<RepositoryApiClient, RepositoryConnection> CONNECTIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RepositoryConnections() {
    }

    /** Returns the connection of the client: always the same one, so that tests can match on it. */
    public static RepositoryConnection of(RepositoryApiClient client) {
        return CONNECTIONS.computeIfAbsent(client, c -> of(c, new HttpCredentials(null, "token")));
    }

    /** Returns a new connection authenticating with the given credentials. */
    public static RepositoryConnection of(RepositoryApiClient client, HttpCredentials credentials) {
        return of(client, credentials, RepositoryRemoteResolver.http(URL), false);
    }

    /** Returns a new connection resolving its Git remotes with {@code remoteResolver}. */
    public static RepositoryConnection of(RepositoryApiClient client, RepositoryRemoteResolver remoteResolver) {
        return of(client, new HttpCredentials(null, "token"), remoteResolver, false);
    }

    /** Returns a new connection with the given credentials, remotes and HTTP authentication mode. */
    public static RepositoryConnection of(RepositoryApiClient client, HttpCredentials credentials,
                                          RepositoryRemoteResolver remoteResolver,
                                          boolean usesGitAuthorizationHeader) {
        return new RepositoryConnection(URL, URL, remoteResolver, usesGitAuthorizationHeader,
                () -> credentials, null, client, Duration.ofMinutes(5));
    }
}
