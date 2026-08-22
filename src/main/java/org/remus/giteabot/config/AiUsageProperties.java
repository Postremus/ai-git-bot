package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the AI usage audit log.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-usage")
public class AiUsageProperties {

    /**
     * Whether to capture and store raw AI request/response payloads in the
     * usage audit log. Disabled by default because the payloads can be large
     * and may contain sensitive prompt content.
     */
    private boolean rawPayloadsEnabled = false;

    /**
     * Maximum length of a raw request/response payload stored in the audit log.
     * Longer payloads are truncated to this length. The database columns
     * {@code ai_usage_log.raw_request} and {@code ai_usage_log.raw_response}
     * are sized for 65535 characters; higher values are capped to that limit.
     */
    private int maxRawPayloadLength = 65535;

    /**
     * Returns the effective maximum raw payload length, capped at the database
     * column size so truncation can never produce a value too large for the
     * schema.
     */
    public int getEffectiveMaxRawPayloadLength() {
        return Math.min(maxRawPayloadLength, 65535);
    }
}
