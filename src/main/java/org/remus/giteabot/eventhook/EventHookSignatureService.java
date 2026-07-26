package org.remus.giteabot.eventhook;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * GitHub-style HMAC-SHA256 payload signing. The signature header
 * ({@value #SIGNATURE_HEADER}) is only sent when the endpoint has a secret
 * configured; consumers must treat it as optional per-endpoint.
 */
@Service
public class EventHookSignatureService {

    public static final String SIGNATURE_HEADER = "X-EventHook-Signature-256";
    public static final String EVENT_HEADER = "X-EventHook-Event";
    public static final String DELIVERY_HEADER = "X-EventHook-Delivery";

    /** Returns {@code sha256=<hex HMAC-SHA256 of body with secret>}. */
    public String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
