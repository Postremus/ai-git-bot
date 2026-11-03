package org.remus.giteabot.gitea;

import lombok.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.RepositoryConnection;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GiteaProviderMetadataTest {

    private GiteaProviderMetadata metadata;

    @BeforeEach
    void setUp() {
        metadata = new GiteaProviderMetadata(builderProvider());
    }

    private static ObjectProvider<RestClient.Builder> builderProvider() {
        return new ObjectProvider<>() {
            @Override
            public @NonNull RestClient.Builder getObject() {
                return RestClient.builder();
            }
        };
    }

    @Test
    void getProviderType_returnsGitea() {
        assertThat(metadata.getProviderType()).isEqualTo(RepositoryType.GITEA);
    }

    @Test
    void resolveApiUrl_returnsOriginalUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitea.example.com");
        integration.setProviderType(RepositoryType.GITEA);

        String apiUrl = metadata.resolveApiUrl(integration);

        assertThat(apiUrl).isEqualTo("https://gitea.example.com");
    }

    @Test
    void resolveCloneUrl_removesApiV1Suffix() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitea.example.com/api/v1");
        integration.setProviderType(RepositoryType.GITEA);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://gitea.example.com");
    }

    @Test
    void resolveCloneUrl_regularUrl_unchanged() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitea.example.com");
        integration.setProviderType(RepositoryType.GITEA);

        String cloneUrl = metadata.resolveCloneUrl(integration);

        assertThat(cloneUrl).isEqualTo("https://gitea.example.com");
    }

    @Test
    void buildAuthorizationHeader_usesTokenPrefix() {
        String header = metadata.buildAuthorizationHeader("mytoken123");

        assertThat(header).isEqualTo("token mytoken123");
    }

    @Test
    void createConnection_http_resolvesRemotesFromTheCloneUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitea.example.com/api/v1");

        RepositoryConnection connection = metadata.createConnection(integration, RestClient.create(),
                () -> new HttpCredentials(null, "token"), null, Duration.ofMinutes(5));

        assertThat(connection.getRepositoryRemote("owner", "repo"))
                .isEqualTo("https://gitea.example.com/owner/repo.git");
        assertThat(connection.usesGitAuthorizationHeader()).isFalse();
    }

    @Test
    void createConnection_ssh_looksUpEachRepositorysSshUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://gitea.example.com");
        integration.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gitea.example.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://gitea.example.com/api/v1/repos/owner/repo"))
                .andRespond(withSuccess("{\"ssh_url\":\"git@gitea.example.com:owner/repo.git\"}",
                        MediaType.APPLICATION_JSON));

        RepositoryConnection connection = metadata.createConnection(integration, builder.build(),
                () -> new HttpCredentials(null, "token"),
                () -> new SshCredentials("key", "gitea.example.com ssh-ed25519 host-key"), Duration.ofMinutes(5));

        assertThat(connection.getRepositoryRemote("owner", "repo"))
                .isEqualTo("git@gitea.example.com:owner/repo.git");
        server.verify();
    }
}
