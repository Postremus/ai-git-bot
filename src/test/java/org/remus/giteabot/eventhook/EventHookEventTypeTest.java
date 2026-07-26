package org.remus.giteabot.eventhook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventHookEventTypeTest {

    @Test
    void wireValue_roundTripsThroughFromWireValue() {
        for (EventHookEventType type : EventHookEventType.values()) {
            assertEquals(type, EventHookEventType.fromWireValue(type.wireValue()));
        }
    }

    @Test
    void wireValues_areDistinct() {
        long distinct = java.util.Arrays.stream(EventHookEventType.values())
                .map(EventHookEventType::wireValue)
                .distinct()
                .count();
        assertEquals(EventHookEventType.values().length, distinct);
    }

    @Test
    void fromWireValue_unknownValue_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EventHookEventType.fromWireValue("garbage"));
        assertTrue(ex.getMessage().contains("garbage"));
    }

    @Test
    void fromWireValue_null_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> EventHookEventType.fromWireValue(null));
    }
}
