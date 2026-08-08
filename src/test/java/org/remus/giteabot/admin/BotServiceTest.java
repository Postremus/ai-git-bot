package org.remus.giteabot.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.systemsettings.BotToolConfiguration;
import org.remus.giteabot.systemsettings.BotToolConfigurationRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BotServiceTest {

    @Mock
    private BotRepository botRepository;

    @Mock
    private BotToolConfigurationRepository botToolConfigurationRepository;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private BotService botService;

    private BotToolConfiguration defaultConfig;

    @BeforeEach
    void setUp() {
        defaultConfig = new BotToolConfiguration();
        defaultConfig.setId(1L);
        defaultConfig.setName("Default");
        defaultConfig.setDefaultEntry(true);
    }

    private Bot newBotWithDefaultToolConfig() {
        Bot bot = new Bot();
        bot.setToolConfiguration(defaultConfig);
        return bot;
    }

    @Test
    void save_generatesWebhookSecret_whenNull() {
        Bot bot = newBotWithDefaultToolConfig();
        bot.setWebhookSecret(null);
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertNotNull(result.getWebhookSecret());
        // UUID format: 8-4-4-4-12 hex characters
        assertTrue(result.getWebhookSecret().matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        verify(botRepository).save(bot);
    }

    @Test
    void save_keepsExistingWebhookSecret() {
        Bot bot = newBotWithDefaultToolConfig();
        bot.setWebhookSecret("existing-secret");
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertEquals("existing-secret", result.getWebhookSecret());
    }

    @Test
    void save_encryptsWebhookSigningSecret() {
        Bot bot = newBotWithDefaultToolConfig();
        bot.setWebhookSigningSecret("plain-signing-secret");
        when(encryptionService.encrypt("plain-signing-secret")).thenReturn("encrypted-signing-secret");
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertEquals("encrypted-signing-secret", result.getWebhookSigningSecret());
        verify(encryptionService).encrypt("plain-signing-secret");
    }

    @Test
    void save_keepsStoredWebhookSigningSecretWhenUpdateLeavesItBlank() {
        Bot bot = newBotWithDefaultToolConfig();
        bot.setId(1L);
        bot.setWebhookSigningSecret(" ");
        Bot existing = new Bot();
        existing.setWebhookSigningSecret("encrypted-signing-secret");
        when(botRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertEquals("encrypted-signing-secret", result.getWebhookSigningSecret());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_clearsStoredWebhookSigningSecretWhenClearRequested() {
        Bot bot = newBotWithDefaultToolConfig();
        bot.setId(1L);
        bot.setWebhookSigningSecret(" ");
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot, true);

        assertNull(result.getWebhookSigningSecret());
        verify(encryptionService, never()).encrypt(anyString());
        verify(botRepository, never()).findById(1L);
    }

    @Test
    void save_newSecretWinsEvenWhenClearRequested() {
        Bot bot = newBotWithDefaultToolConfig();
        bot.setId(1L);
        bot.setWebhookSigningSecret("plain-signing-secret");
        when(encryptionService.encrypt("plain-signing-secret")).thenReturn("encrypted-signing-secret");
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot, true);

        assertEquals("encrypted-signing-secret", result.getWebhookSigningSecret());
        verify(encryptionService).encrypt("plain-signing-secret");
    }

    @Test
    void getDecryptedWebhookSigningSecret_decryptsStoredSecret() {
        Bot bot = new Bot();
        bot.setWebhookSigningSecret("encrypted-signing-secret");
        when(encryptionService.decrypt("encrypted-signing-secret")).thenReturn("plain-signing-secret");

        String result = botService.getDecryptedWebhookSigningSecret(bot);

        assertEquals("plain-signing-secret", result);
        verify(encryptionService).decrypt("encrypted-signing-secret");
    }

    @Test
    void save_preservesAgentEnabledFlag() {
        // The deprecated botType no longer forces agentEnabled off; the flag
        // is an independent toggle consumed only by the issue-coding workflow.
        Bot bot = newBotWithDefaultToolConfig();
        bot.setWebhookSecret("existing-secret");
        bot.setAgentEnabled(true);
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertTrue(result.isAgentEnabled());
    }

    @Test
    void save_assignsDefaultToolConfiguration_whenMissing() {
        Bot bot = new Bot();
        bot.setWebhookSecret("existing-secret");
        when(botToolConfigurationRepository.findByDefaultEntryTrue())
                .thenReturn(Optional.of(defaultConfig));
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Bot result = botService.save(bot);

        assertSame(defaultConfig, result.getToolConfiguration());
        verify(botToolConfigurationRepository).findByDefaultEntryTrue();
    }

    @Test
    void save_failsFast_whenNoToolConfigurationAndNoDefault() {
        Bot bot = new Bot();
        bot.setWebhookSecret("existing-secret");
        when(botToolConfigurationRepository.findByDefaultEntryTrue())
                .thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> botService.save(bot));
        assertTrue(ex.getMessage().contains("No tool configuration"),
                "Expected fail-fast message, got: " + ex.getMessage());
        verify(botRepository, never()).save(any(Bot.class));
    }

    @Test
    void incrementWebhookCallCount_incrementsAndSetsTimestamp() {
        Bot bot = new Bot();
        bot.setWebhookCallCount(5);
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        botService.incrementWebhookCallCount(bot);

        assertEquals(6, bot.getWebhookCallCount());
        assertNotNull(bot.getLastWebhookAt());
        verify(botRepository).save(bot);
    }

    @Test
    void recordError_setsErrorInfo() {
        Bot bot = new Bot();
        when(botRepository.save(any(Bot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        botService.recordError(bot, "Something went wrong");

        assertEquals("Something went wrong", bot.getLastErrorMessage());
        assertNotNull(bot.getLastErrorAt());
        verify(botRepository).save(bot);
    }

    @Test
    void findByWebhookSecret_delegatesToRepository() {
        Bot bot = new Bot();
        bot.setWebhookSecret("test-secret");
        when(botRepository.findByWebhookSecret("test-secret")).thenReturn(Optional.of(bot));

        Optional<Bot> result = botService.findByWebhookSecret("test-secret");

        assertTrue(result.isPresent());
        assertEquals("test-secret", result.get().getWebhookSecret());
        verify(botRepository).findByWebhookSecret("test-secret");
    }
}
