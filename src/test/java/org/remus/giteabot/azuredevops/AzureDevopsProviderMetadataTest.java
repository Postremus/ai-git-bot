package org.remus.giteabot.azuredevops;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.GitIntegration;
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
    }
}
