package org.remus.giteabot.webhook;

import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.RepositoryType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "webhook-secret";
    private static final byte[] BODY = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void isValid_allowsUnsignedWebhooksWhenNoSigningSecretIsConfigured() {
        assertTrue(WebhookSignatureVerifier.isValid(RepositoryType.GITHUB, null, Map.of(), BODY));
        assertTrue(WebhookSignatureVerifier.isValid(RepositoryType.GITHUB, " ", Map.of(), BODY));
    }

    @Test
    void isValid_acceptsGitHubSignatureCaseInsensitively() {
        String signature = "sha256=" + hmacSha256(SECRET, BODY);

        assertTrue(WebhookSignatureVerifier.isValid(
                RepositoryType.GITHUB, SECRET, Map.of("x-hub-signature-256", signature), BODY));
    }

    @Test
    void isValid_acceptsGiteaSignature() {
        assertTrue(WebhookSignatureVerifier.isValid(
                RepositoryType.GITEA, SECRET, Map.of("X-Gitea-Signature", hmacSha256(SECRET, BODY)), BODY));
    }

    @Test
    void isValid_acceptsGitLabToken() {
        assertTrue(WebhookSignatureVerifier.isValid(
                RepositoryType.GITLAB, SECRET, Map.of("X-Gitlab-Token", SECRET), BODY));
    }

    @Test
    void isValid_acceptsBitbucketSignature() {
        String signature = "sha256=" + hmacSha256(SECRET, BODY);

        assertTrue(WebhookSignatureVerifier.isValid(
                RepositoryType.BITBUCKET, SECRET, Map.of("X-Hub-Signature", signature), BODY));
    }

    @Test
    void isValid_rejectsInvalidSignature() {
        assertFalse(WebhookSignatureVerifier.isValid(
                RepositoryType.GITHUB, SECRET, Map.of("X-Hub-Signature-256", "sha256=invalid"), BODY));
    }

    @Test
    void isValid_rejectsInvalidGiteaGitLabAndBitbucketValues() {
        assertFalse(WebhookSignatureVerifier.isValid(
                RepositoryType.GITEA, SECRET, Map.of("X-Gitea-Signature", "invalid"), BODY));
        assertFalse(WebhookSignatureVerifier.isValid(
                RepositoryType.GITLAB, SECRET, Map.of("X-Gitlab-Token", "invalid"), BODY));
        assertFalse(WebhookSignatureVerifier.isValid(
                RepositoryType.BITBUCKET, SECRET, Map.of("X-Hub-Signature", "sha256=invalid"), BODY));
    }

    private static String hmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
