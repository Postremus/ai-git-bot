package org.remus.giteabot.azuredevops;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;
import org.remus.giteabot.repository.RepositoryConnection;
import org.remus.giteabot.repository.RepositoryType;
import org.remus.giteabot.repository.model.HttpCredentials;

import static org.junit.jupiter.api.Assertions.*;

class AzureDevopsProviderMetadataTest {

    private final AzureDevopsProviderMetadata metadata =
            new AzureDevopsProviderMetadata(null, null);

    @Test
    void providerType_isAzureDevops() {
        assertEquals(RepositoryType.AZURE_DEVOPS, metadata.getProviderType());
    }

    @Test
    void resolveApiUrl_defaultsToPublicService() {
        GitIntegration integration = new GitIntegration();
        assertEquals("https://dev.azure.com", metadata.resolveApiUrl(integration));
    }

    @Test
    void resolveApiUrl_stripsTrailingSlash() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://dev.azure.com/");
        assertEquals("https://dev.azure.com", metadata.resolveApiUrl(integration));
    }

    @Test
    void buildAuthorizationHeader_usesBasicWithEmptyUsername() {
        // Azure DevOps PATs authenticate as Basic with an empty username.
        assertEquals("Basic " + java.util.Base64.getEncoder()
                        .encodeToString(":ado_pat".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                metadata.buildAuthorizationHeader("ado_pat"));
    }

    @Test
    void buildAuthorizationHeader_blankTokenYieldsEmpty() {
        assertEquals("", metadata.buildAuthorizationHeader(null));
        assertEquals("", metadata.buildAuthorizationHeader("  "));
    }

    @Test
    void createConnection_holdsTheUrlsAndAnAzureDevopsClient() {
        GitIntegration integration = new GitIntegration();
        integration.setUrl("https://dev.azure.com/");

        RepositoryConnection connection = metadata.createConnection(
                integration, null, () -> new HttpCredentials(null, "ado_pat"), null, Duration.ofMinutes(5));

        assertInstanceOf(AzureDevopsApiClient.class, connection.getClient());
        assertEquals("https://dev.azure.com", connection.getBaseUrl());
        assertEquals("https://dev.azure.com", connection.getCloneUrl());
        assertEquals(new HttpCredentials(null, "ado_pat"), connection.getCredentials());
        assertTrue(connection.usesGitAuthorizationHeader());
        assertEquals("https://dev.azure.com/contoso/MyProject/_git/my-service",
                connection.getRepositoryRemote("contoso", "MyProject/my-service"));
    }

    @Test
    void remoteResolver_omitsTheOrganizationWhenTheCloneBaseAlreadyCarriesIt() {
        assertEquals("https://contoso.visualstudio.com/MyProject/_git/my-service",
                AzureDevopsProviderMetadata.remoteResolver("https://contoso.visualstudio.com")
                        .resolve("contoso", "MyProject/my-service"));

        assertEquals("https://tfs.example.com/tfs/DefaultCollection/MyProject/_git/my-service",
                AzureDevopsProviderMetadata.remoteResolver("https://tfs.example.com/tfs/DefaultCollection")
                        .resolve("DefaultCollection", "MyProject/my-service"));
    }

    @Test
    void remoteResolver_rejectsCredentialBearingHttpBase() {
        // The remote is passed to git as a command-line argument and echoed back in its
        // error output, so a PAT in the clone base URL must not survive into it. The
        // Azure DevOps resolver builds its own path and must not lose the guarantee
        // RepositoryRemoteResolver.validatedCloneBaseUrl gives.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> AzureDevopsProviderMetadata.remoteResolver("https://user:pat@dev.azure.com")
                        .resolve("contoso", "MyProject/my-service"));
        assertEquals("HTTP clone base URL must be credential-free", e.getMessage());
    }

    @Test
    void remoteResolver_rejectsUnsupportedScheme() {
        assertThrows(IllegalStateException.class,
                () -> AzureDevopsProviderMetadata.remoteResolver("ssh://dev.azure.com")
                        .resolve("contoso", "MyProject/my-service"));
    }

    @Test
    void remoteResolver_usesGitSegment() {
        assertEquals("https://dev.azure.com/contoso/MyProject/_git/my-service",
                AzureDevopsProviderMetadata.remoteResolver("https://dev.azure.com")
                        .resolve("contoso", "MyProject/my-service"));
    }
}
