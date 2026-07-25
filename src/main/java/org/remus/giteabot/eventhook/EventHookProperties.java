package org.remus.giteabot.eventhook;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Outgoing-webhook configuration, prefix {@code eventhook}. All properties are
 * env-overridable ({@code EVENTHOOK_ENABLED}, {@code EVENTHOOK_RETRY_MAX_ATTEMPTS},
 * {@code EVENTHOOK_RETENTION_KEEP_LAST}, ...).
 */
@Data
@Component
@ConfigurationProperties(prefix = "eventhook")
public class EventHookProperties {

    private boolean enabled = true;

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(10);

    private Duration sweeperInterval = Duration.ofSeconds(30);

    private int maxPayloadBytes = 64 * 1024;

    private Retry retry = new Retry();

    private Retention retention = new Retention();

    @Data
    public static class Retry {
        private int maxAttempts = 5;
        private Duration initialBackoff = Duration.ofSeconds(30);
        private double backoffMultiplier = 2.0;
        private Duration maxBackoff = Duration.ofMinutes(30);
    }

    @Data
    public static class Retention {
        /** Newest deliveries kept per endpoint (any status); 0 = prune all terminal rows. */
        private int keepLast = 10;
        /** GC schedule, cron expression (server time). */
        private String gcCron = "0 41 4 * * *";
    }

    /** Exponential backoff for the given 1-based attempt, capped at {@code retry.max-backoff}. */
    public Duration backoffForAttempt(int attempt) {
        double seconds = retry.initialBackoff.toSeconds() * Math.pow(retry.backoffMultiplier, attempt - 1);
        return Duration.ofSeconds((long) Math.min(seconds, retry.maxBackoff.toSeconds()));
    }
}
