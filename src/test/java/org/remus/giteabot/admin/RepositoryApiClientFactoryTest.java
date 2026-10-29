package org.remus.giteabot.admin;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.gitea.GiteaApiClient;
import org.remus.giteabot.repository.GitTransport;
import org.remus.giteabot.repository.RepositoryProviderMetadata;
import org.remus.giteabot.repository.RepositoryProviderRegistry;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.RepositoryConnection;
import org.remus.giteabot.repository.model.HttpCredentials;
import org.remus.giteabot.repository.model.SshCredentials;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryApiClientFactoryTest {

    @Mock
    private GitIntegrationService integrationService;
    @Mock
    private RepositoryProviderRegistry providerRegistry;
    @Mock
    private RepositoryProviderMetadata provider;
    @Mock
    private RestClient restClient;

    @InjectMocks
    private RepositoryApiClientFactory factory;

    @BeforeEach
    void setUp() {
        lenient().when(providerRegistry.getProvider(RepositoryType.GITEA)).thenReturn(provider);
        lenient().when(provider.buildRestClient(any(), anyString())).thenReturn(restClient);
        lenient().when(provider.createHttpCredentials(any(), anyString())).thenAnswer(invocation ->
                new HttpCredentials(null, invocation.getArgument(1)));
        lenient().when(provider.createConnection(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            GitIntegration integration = invocation.getArgument(0);
            return new RepositoryConnection(integration.getUrl(), integration.getUrl(),
                    invocation.getArgument(2), invocation.getArgument(3),
                    new GiteaApiClient(restClient, integration.getUrl()), Duration.ofMinutes(5));
        });
    }

    @Test
    void clientsKeepTransportAndSshCredentialsPerIntegration() {
        GitIntegration ssh = integration(1L, "SSH", GitTransport.SSH);
        ssh.setSshPrivateKey("encrypted-key");
        ssh.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        GitIntegration http = integration(2L, "HTTP", GitTransport.HTTP);
        lenient().when(integrationService.decryptToken(ssh)).thenReturn("ssh-token");
        lenient().when(integrationService.decryptToken(http)).thenReturn("http-token");
        lenient().when(integrationService.decryptSshPrivateKey(ssh)).thenReturn("plain-private-key");

        var sshCredentials = factory.getConnection(ssh).getCredentials();
        var httpCredentials = factory.getConnection(http).getCredentials();

        assertThat(sshCredentials).isEqualTo(
                new SshCredentials("plain-private-key", "gitea.example.com ssh-ed25519 host-key"));
        assertThat(httpCredentials).isEqualTo(new HttpCredentials(null, "http-token"));
        verify(integrationService, never()).decryptSshPrivateKey(http);
    }

    @Test
    void credentialsToString_redactsSecrets() {
        GitIntegration ssh = integration(1L, "SSH", GitTransport.SSH);
        ssh.setSshPrivateKey("encrypted-key");
        ssh.setSshKnownHosts("gitea.example.com ssh-ed25519 host-key");
        lenient().when(integrationService.decryptToken(ssh)).thenReturn("ssh-token");
        lenient().when(integrationService.decryptSshPrivateKey(ssh)).thenReturn("plain-private-key");

        String toString = factory.getConnection(ssh).getCredentials().toString();

        assertThat(toString)
                .doesNotContain("ssh-token")
                .doesNotContain("plain-private-key")
                .doesNotContain("host-key");
    }

    @Test
    void sshPrivateKey_isOnlyDecryptedWhenTheCredentialsAreRead() {
        GitIntegration integration = integration(1L, "SSH", GitTransport.SSH);
        integration.setSshPrivateKey("encrypted-key");
        lenient().when(integrationService.decryptToken(integration)).thenReturn("token");
        lenient().when(integrationService.decryptSshPrivateKey(integration)).thenReturn("private-key");

        RepositoryConnection connection = factory.getConnection(integration);
        verify(integrationService, never()).decryptSshPrivateKey(integration);

        connection.getCredentials();
        verify(integrationService, times(1)).decryptSshPrivateKey(integration);
    }

    @Test
    void sshConnectionsAreCachedWithoutKeepingTheKeyUntilTheIntegrationChanges() {
        GitIntegration integration = integration(1L, "SSH", GitTransport.SSH);
        integration.setSshPrivateKey("encrypted-key");
        lenient().when(integrationService.decryptToken(integration)).thenReturn("token");
        lenient().when(integrationService.decryptSshPrivateKey(integration)).thenReturn("private-key");

        RepositoryConnection connection = factory.getConnection(integration);

        assertThat(factory.getConnection(integration)).isSameAs(connection);
        verify(integrationService, never()).decryptSshPrivateKey(integration);

        integration.setUpdatedAt(integration.getUpdatedAt().plusSeconds(1));
        assertThat(factory.getConnection(integration)).isNotSameAs(connection);
    }

    @Test
    void replacementTokenClient_isUncachedAndDoesNotDecryptSshSecrets() {
        GitIntegration integration = integration(7L, "SSH", GitTransport.SSH);
        var first = factory.createApiClient(integration, "new-token");
        var second = factory.createApiClient(integration, "new-token");
        assertThat(first).isNotSameAs(second);
        verify(integrationService, never()).decryptToken(any());
        verify(integrationService, never()).decryptSshPrivateKey(any());
    }

    private GitIntegration integration(long id, String name, GitTransport transport) {
        GitIntegration integration = new GitIntegration();
        integration.setId(id);
        integration.setName(name);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setUrl("https://gitea.example.com/" + name.toLowerCase());
        integration.setTransport(transport);
        integration.setUpdatedAt(Instant.parse("2026-08-31T12:00:00Z"));
        return integration;
    }
}
