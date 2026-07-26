package org.remus.giteabot.eventhook;

/**
 * Lifecycle of a single delivery attempt row. {@code PENDING} and
 * {@code RETRYING} are in-flight states; {@code SUCCESS} and
 * {@code FAILED} are terminal.
 */
public enum DeliveryStatus {
    PENDING,
    SUCCESS,
    RETRYING,
    FAILED
}
