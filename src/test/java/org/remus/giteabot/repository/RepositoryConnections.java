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
        return new RepositoryConnection("https://git.example.com", "https://git.example.com",
                () -> credentials, null, client, Duration.ofMinutes(5));
    }
}
