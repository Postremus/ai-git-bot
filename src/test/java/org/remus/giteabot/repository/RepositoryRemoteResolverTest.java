package org.remus.giteabot.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryRemoteResolverTest {

    @Test
    void http_buildsOwnerRepoGitRemote() {
        assertEquals("https://git.example.com/owner/repo.git",
                RepositoryRemoteResolver.http("https://git.example.com/").resolve("owner", "repo"));
    }

    @Test
    void http_acceptsCaseInsensitiveHttpScheme() {
        assertEquals("https://gitea.example.com/owner/repo.git",
                RepositoryRemoteResolver.http("HTTPS://gitea.example.com").resolve("owner", "repo"));
    }

    @Test
    void http_rejectsCredentialBearingBaseOnResolveOnly() {
        // Building the connection must not fail: the API operations that share it still work.
        RepositoryRemoteResolver resolver = RepositoryRemoteResolver.http("https://user:secret@gitea.example.com");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> resolver.resolve("owner", "repo"));
        assertEquals("HTTP clone base URL must be credential-free", e.getMessage());
    }

    @Test
    void http_rejectsUnsupportedScheme() {
        RepositoryRemoteResolver resolver = RepositoryRemoteResolver.http("ftp://gitea.example.com");

        assertThrows(IllegalStateException.class, () -> resolver.resolve("owner", "repo"));
    }

    @Test
    void http_rejectsMissingCoordinates() {
        RepositoryRemoteResolver resolver = RepositoryRemoteResolver.http("https://git.example.com");

        assertThrows(IllegalStateException.class, () -> resolver.resolve(" ", "repo"));
        assertThrows(IllegalStateException.class, () -> resolver.resolve("owner", null));
    }
}
