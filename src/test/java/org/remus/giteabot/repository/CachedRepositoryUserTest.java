package org.remus.giteabot.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.RepositoryUser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CachedRepositoryUserTest {

    private static final Instant START = Instant.parse("2026-10-27T10:00:00Z");
    private static final RepositoryUser BOT = new RepositoryUser("17", "ai_bot", "AI Bot", "bot@example.com");

    @Mock private RepositoryApiClient client;

    private final MutableClock clock = new MutableClock(START);
    private CachedRepositoryUser user;

    @BeforeEach
    void setUp() {
        user = new CachedRepositoryUser(() -> client.getAuthenticatedUser("owner"), Duration.ofMinutes(10), clock);
    }

    @Test
    void cachesTheUserUntilTheTtlExpires() {
        when(client.getAuthenticatedUser("owner")).thenReturn(BOT, new RepositoryUser("17", "renamed", null, "bot@example.com"));

        assertEquals(BOT, user.get());
        clock.advance(Duration.ofMinutes(9));
        assertEquals(BOT, user.get());
        verify(client, times(1)).getAuthenticatedUser("owner");

        clock.advance(Duration.ofMinutes(2));
        assertEquals("renamed", user.get().username());
        verify(client, times(2)).getAuthenticatedUser("owner");
    }

    @Test
    void keepsServingTheExpiredUserWhenTheRefreshFails() {
        when(client.getAuthenticatedUser("owner")).thenReturn(BOT).thenThrow(new IllegalStateException("503"));
        user.get();
        clock.advance(Duration.ofMinutes(11));

        assertEquals(BOT, user.get());
    }

    @Test
    void waitsTheRetryDelayBeforeAskingTheProviderAgainAfterAFailedRefresh() {
        when(client.getAuthenticatedUser("owner")).thenReturn(BOT).thenThrow(new IllegalStateException("503"));
        user.get();
        clock.advance(Duration.ofMinutes(11));
        user.get();

        clock.advance(CachedRepositoryUser.RETRY_DELAY.minusSeconds(1));
        assertEquals(BOT, user.get());
        verify(client, times(2)).getAuthenticatedUser("owner");

        clock.advance(Duration.ofSeconds(1));
        assertEquals(BOT, user.get());
        verify(client, times(3)).getAuthenticatedUser("owner");
    }

    @Test
    void failsWhenNothingIsCachedAndTheProviderCannotBeAsked() {
        when(client.getAuthenticatedUser("owner")).thenThrow(new IllegalStateException("401 Unauthorized"));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> user.get());

        assertEquals("Could not determine the Git user: 401 Unauthorized", error.getMessage());
    }

    @Test
    void connection_asksTheProviderOnlyWhenTheUserIsRead() {
        RepositoryConnection connection = new RepositoryConnection("https://git.example.com", "https://git.example.com",
                RepositoryRemoteResolver.http("https://git.example.com"), false,
                () -> new HttpCredentials(null, "token"), null, client, Duration.ofMinutes(10));
        verifyNoInteractions(client);
        when(client.getAuthenticatedUser("owner")).thenReturn(BOT);

        assertEquals(BOT, connection.getUser("owner"));
        assertEquals(BOT, connection.getUser("owner"));

        verify(client, times(1)).getAuthenticatedUser("owner");
    }

    @Test
    void connection_sharesOneUserAcrossOwnersByDefault() {
        when(client.identityScope(anyString())).thenReturn("");
        when(client.getAuthenticatedUser("first")).thenReturn(BOT);
        RepositoryConnection connection = connection();

        assertEquals(BOT, connection.getUser("first"));
        assertEquals(BOT, connection.getUser("second"));

        verify(client, times(1)).getAuthenticatedUser(anyString());
    }

    @Test
    void connection_asksOncePerIdentityScope() {
        // Azure DevOps scopes the identity per organization.
        when(client.identityScope(anyString())).thenAnswer(invocation -> invocation.<String>getArgument(0).toLowerCase());
        RepositoryUser other = new RepositoryUser("17", "bot", null, "bot@fabrikam.com");
        when(client.getAuthenticatedUser("contoso")).thenReturn(BOT);
        when(client.getAuthenticatedUser("fabrikam")).thenReturn(other);
        RepositoryConnection connection = connection();

        assertEquals(BOT, connection.getUser("contoso"));
        assertEquals(BOT, connection.getUser("Contoso"));
        assertEquals(other, connection.getUser("fabrikam"));

        verify(client, times(1)).getAuthenticatedUser("contoso");
        verify(client, times(1)).getAuthenticatedUser("fabrikam");
        verify(client, never()).getAuthenticatedUser("Contoso");
    }

    private RepositoryConnection connection() {
        return new RepositoryConnection("https://git.example.com", "https://git.example.com",
                RepositoryRemoteResolver.http("https://git.example.com"), false,
                () -> new HttpCredentials(null, "token"), null, client, Duration.ofMinutes(10));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
