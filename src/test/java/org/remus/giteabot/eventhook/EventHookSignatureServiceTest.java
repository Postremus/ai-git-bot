package org.remus.giteabot.eventhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EventHookSignatureServiceTest {

    private final EventHookSignatureService service = new EventHookSignatureService();

    @Test
    void sign_matchesRfc4231TestVector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        String signature = service.sign(
                "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8), "Jefe");

        assertEquals("sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                signature);
    }

    @Test
    void sign_isDeterministic() {
        byte[] body = "{\"eventType\":\"prworkflow.completed\"}".getBytes(StandardCharsets.UTF_8);

        assertEquals(service.sign(body, "s3cret"), service.sign(body, "s3cret"));
    }

    @Test
    void sign_differentSecretsProduceDifferentSignatures() {
        byte[] body = "body".getBytes(StandardCharsets.UTF_8);

        assertNotEquals(service.sign(body, "secret-a"), service.sign(body, "secret-b"));
    }

    @Test
    void sign_differentBodiesProduceDifferentSignatures() {
        assertNotEquals(
                service.sign("a".getBytes(StandardCharsets.UTF_8), "secret"),
                service.sign("b".getBytes(StandardCharsets.UTF_8), "secret"));
    }
}
