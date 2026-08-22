package org.remus.giteabot.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class GitIntegrationService {

    private final GitIntegrationRepository gitIntegrationRepository;
    private final EncryptionService encryptionService;

    @Transactional(readOnly = true)
    public List<GitIntegration> findAll() {
        return gitIntegrationRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<GitIntegration> findById(Long id) {
        return gitIntegrationRepository.findById(id);
    }

    /**
     * Saves a Git integration, resolving the token from the form input.
     *
     * <p>The token field is a one-way write: the stored value is never echoed
     * back into the form. A blank field therefore means "keep the stored
     * value", while {@code clearToken} requests explicit removal (the Clear
     * button in the UI). Re-encrypting the kept ciphertext would corrupt the
     * token, so only freshly provided plaintext tokens are encrypted.</p>
     */
    public GitIntegration save(GitIntegration integration, boolean clearToken) {
        // Set default URLs for providers that don't require user input
        if (integration.getProviderType() == RepositoryType.GITHUB) {
            integration.setUrl("https://github.com");
        } else if (integration.getProviderType() == RepositoryType.BITBUCKET) {
            integration.setUrl("https://bitbucket.org");
        }

        String token = integration.getToken();
        if (token != null && !token.isBlank()) {
            integration.setToken(encryptionService.encrypt(token));
        } else if (clearToken) {
            integration.setToken(null);
        } else if (integration.getId() != null) {
            // Keep existing token if not provided on update
            gitIntegrationRepository.findById(integration.getId())
                    .ifPresent(existing -> integration.setToken(existing.getToken()));
        }
        return gitIntegrationRepository.save(integration);
    }

    public void deleteById(Long id) {
        gitIntegrationRepository.deleteById(id);
    }

    public String decryptToken(GitIntegration integration) {
        String token = integration.getToken();
        if (token == null || token.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(token);
    }
}
