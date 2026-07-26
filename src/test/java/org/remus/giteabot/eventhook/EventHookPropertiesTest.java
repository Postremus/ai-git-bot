package org.remus.giteabot.eventhook;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class EventHookPropertiesTest {

    @Test
    void defaults_matchDocumentedValues() {
        EventHookProperties props = new EventHookProperties();

        assertTrue(props.isEnabled());
        assertEquals(Duration.ofSeconds(5), props.getConnectTimeout());
        assertEquals(Duration.ofSeconds(10), props.getReadTimeout());
        assertEquals(Duration.ofSeconds(30), props.getSweeperInterval());
        assertEquals(64 * 1024, props.getMaxPayloadBytes());
        assertEquals(5, props.getRetry().getMaxAttempts());
        assertEquals(Duration.ofSeconds(30), props.getRetry().getInitialBackoff());
        assertEquals(2.0, props.getRetry().getBackoffMultiplier());
        assertEquals(Duration.ofMinutes(30), props.getRetry().getMaxBackoff());
        assertEquals(10, props.getRetention().getKeepLast());
        assertEquals("0 41 4 * * *", props.getRetention().getGcCron());
    }

    @Test
    void backoffForAttempt_growsExponentially() {
        EventHookProperties props = new EventHookProperties();

        assertEquals(Duration.ofSeconds(30), props.backoffForAttempt(1));
        assertEquals(Duration.ofSeconds(60), props.backoffForAttempt(2));
        assertEquals(Duration.ofSeconds(120), props.backoffForAttempt(3));
        assertEquals(Duration.ofSeconds(240), props.backoffForAttempt(4));
    }

    @Test
    void backoffForAttempt_capsAtMaxBackoff() {
        EventHookProperties props = new EventHookProperties();
        props.getRetry().setMaxBackoff(Duration.ofSeconds(90));

        assertEquals(Duration.ofSeconds(30), props.backoffForAttempt(1));
        assertEquals(Duration.ofSeconds(60), props.backoffForAttempt(2));
        assertEquals(Duration.ofSeconds(90), props.backoffForAttempt(3));
        assertEquals(Duration.ofSeconds(90), props.backoffForAttempt(10));
    }

    @Test
    void backoffForAttempt_honoursCustomMultiplier() {
        EventHookProperties props = new EventHookProperties();
        props.getRetry().setInitialBackoff(Duration.ofSeconds(10));
        props.getRetry().setBackoffMultiplier(3.0);

        assertEquals(Duration.ofSeconds(10), props.backoffForAttempt(1));
        assertEquals(Duration.ofSeconds(30), props.backoffForAttempt(2));
        assertEquals(Duration.ofSeconds(90), props.backoffForAttempt(3));
    }
}
