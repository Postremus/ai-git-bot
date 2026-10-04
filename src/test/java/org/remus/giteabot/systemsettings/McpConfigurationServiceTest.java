package org.remus.giteabot.systemsettings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.admin.Bot;
import org.remus.giteabot.admin.BotRepository;
import org.remus.giteabot.admin.EncryptionService;
import org.remus.giteabot.mcp.McpConfigurationParser;
import org.remus.giteabot.secret.FakeSecretSource;
import org.remus.giteabot.secret.SecretSourceRegistry;
import org.remus.giteabot.secret.SecretTemplateParser;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpConfigurationServiceTest {

    @Mock
    private McpConfigurationRepository mcpConfigurationRepository;

    @Mock
    private BotRepository botRepository;

    /** Real encryption with a test key so save() encrypts the JSON content. */
    @Spy
    private EncryptionService encryptionService = new EncryptionService("test-key");

    /** Real parser, so save() checks the secret references against a known set of keys. */
    @Spy
    private McpConfigurationParser mcpConfigurationParser = new McpConfigurationParser(
            new SecretTemplateParser(new SecretSourceRegistry(List.of(
                    FakeSecretSource.of("env", Map.of("MCP_TOKEN", "s3cret"))))));

    @InjectMocks
    private McpConfigurationService mcpConfigurationService;

    @Test
    void save_rejectsInvalidJson() {
        McpConfiguration mcpConfiguration = configuration("{not json}");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mcpConfigurationService.save(mcpConfiguration));

        assertEquals("MCP configuration must be valid JSON", exception.getMessage());
        verify(mcpConfigurationRepository, never()).save(any());
    }

    @Test
    void save_rejectsStdioTransport() {
        McpConfiguration mcpConfiguration = configuration("""
                {"name":"local","transport":"stdio","command":"github-mcp-server"}
                """);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mcpConfigurationService.save(mcpConfiguration));

        assertEquals("stdio MCP transport is not supported", exception.getMessage());
        verify(mcpConfigurationRepository, never()).save(any());
    }

    @Test
    void save_acceptsRemoteTransport() {
        String jsonContent = """
                {"name":"github","type":"url","url":"https://api.githubcopilot.com/mcp/"}
                """;
        McpConfiguration mcpConfiguration = configuration(jsonContent);
        when(mcpConfigurationRepository.save(mcpConfiguration)).thenReturn(mcpConfiguration);

        McpConfiguration result = mcpConfigurationService.save(mcpConfiguration);

        assertSame(mcpConfiguration, result);
        assertNotEquals(jsonContent, result.getJsonContent());
        assertEquals(jsonContent, mcpConfigurationService.getDecryptedJsonContent(result));
        assertEquals(jsonContent, mcpConfigurationService.decryptedView(result).getJsonContent());
        verify(mcpConfigurationRepository).save(mcpConfiguration);
    }

    @Test
    void save_acceptsResolvableSecretReferences() {
        McpConfiguration mcpConfiguration = configuration("""
                {"name":"github","type":"url","url":"https://api.githubcopilot.com/mcp/",
                 "token":"${env:MCP_TOKEN}","headers":{"X-Api-Key":"${env:MCP_TOKEN}"}}
                """);
        when(mcpConfigurationRepository.save(mcpConfiguration)).thenReturn(mcpConfiguration);

        mcpConfigurationService.save(mcpConfiguration);

        verify(mcpConfigurationRepository).save(mcpConfiguration);
    }

    @Test
    void save_rejectsUnresolvableSecretReferences() {
        McpConfiguration mcpConfiguration = configuration("""
                {"name":"github","type":"url","url":"https://api.githubcopilot.com/mcp/",
                 "token":"${env:MISSING}","headers":{"X-Api-Key":"${vault:MCP_TOKEN}"}}
                """);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mcpConfigurationService.save(mcpConfiguration));

        assertEquals("Server 'github', authorization token: ${env:MISSING}: Key MISSING is not resolvable; "
                + "Server 'github', header 'X-Api-Key': ${vault:MCP_TOKEN}: Could not find secret source for type vault",
                exception.getMessage());
        verify(mcpConfigurationRepository, never()).save(any());
    }

    @Test
    void deleteById_configurationUsedByBots_throwsWithBotNames() {
        McpConfiguration mcpConfiguration = configuration("""
                {"name":"github","type":"url","url":"https://api.githubcopilot.com/mcp/"}
                """);
        mcpConfiguration.setId(1L);
        Bot bot = new Bot();
        bot.setName("Review Bot");
        when(mcpConfigurationRepository.findById(1L)).thenReturn(Optional.of(mcpConfiguration));
        when(botRepository.findByMcpConfigurationId(1L)).thenReturn(List.of(bot));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> mcpConfigurationService.deleteById(1L));

        assertTrue(exception.getMessage().contains("Review Bot"));
        verify(mcpConfigurationRepository, never()).delete(any());
    }

    private McpConfiguration configuration(String jsonContent) {
        McpConfiguration mcpConfiguration = new McpConfiguration();
        mcpConfiguration.setName("GitHub MCP");
        mcpConfiguration.setJsonContent(jsonContent);
        return mcpConfiguration;
    }
}
