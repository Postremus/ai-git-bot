package org.remus.giteabot.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.remus.giteabot.repository.RepositoryType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitIntegrationServiceTest {

    @Mock
    private GitIntegrationRepository gitIntegrationRepository;

    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private GitIntegrationService gitIntegrationService;

    @Test
    void save_encryptsToken() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken("plain-token");
        when(encryptionService.encrypt("plain-token")).thenReturn("encrypted-value");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertEquals("encrypted-value", result.getToken());
        verify(encryptionService).encrypt("plain-token");
    }

    @Test
    void save_blankTokenOnUpdate_keepsStoredTokenWithoutReEncrypting() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken("");
        GitIntegration existing = new GitIntegration();
        existing.setToken("stored-encrypted-token");
        when(gitIntegrationRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertEquals("stored-encrypted-token", result.getToken());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_clearToken_removesStoredToken() {
        GitIntegration integration = new GitIntegration();
        integration.setId(7L);
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken("");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, true);

        assertNull(result.getToken());
        verify(gitIntegrationRepository, never()).findById(anyLong());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_nullToken_staysNull() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITEA);
        integration.setToken(null);
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertNull(result.getToken());
        verify(encryptionService, never()).encrypt(anyString());
    }

    @Test
    void save_githubProvider_setsDefaultUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.GITHUB);
        integration.setToken("gh-token");
        when(encryptionService.encrypt("gh-token")).thenReturn("encrypted");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertEquals("https://github.com", result.getUrl());
    }

    @Test
    void save_bitbucketProvider_setsDefaultUrl() {
        GitIntegration integration = new GitIntegration();
        integration.setProviderType(RepositoryType.BITBUCKET);
        integration.setToken("bb-token");
        when(encryptionService.encrypt("bb-token")).thenReturn("encrypted");
        when(gitIntegrationRepository.save(any(GitIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GitIntegration result = gitIntegrationService.save(integration, false);

        assertEquals("https://bitbucket.org", result.getUrl());
    }

    @Test
    void decryptToken_callsDecrypt() {
        GitIntegration integration = new GitIntegration();
        integration.setToken("encrypted-value");
        when(encryptionService.decrypt("encrypted-value")).thenReturn("plain-token");

        String result = gitIntegrationService.decryptToken(integration);

        assertEquals("plain-token", result);
        verify(encryptionService).decrypt("encrypted-value");
    }

    @Test
    void decryptToken_nullToken_returnsNull() {
        GitIntegration integration = new GitIntegration();
        integration.setToken(null);

        String result = gitIntegrationService.decryptToken(integration);

        assertNull(result);
        verify(encryptionService, never()).decrypt(anyString());
    }

    @Test
    void deleteById_delegatesToRepository() {
        gitIntegrationService.deleteById(1L);

        verify(gitIntegrationRepository).deleteById(1L);
    }
}
