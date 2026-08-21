package org.remus.giteabot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Opt-in adaptive extended thinking for the Anthropic provider.
 *
 * <p>Without extended thinking the model has no separate channel for its
 * reasoning, so it narrates inline and that narration ends up verbatim in the
 * posted review comment. With adaptive thinking enabled, reasoning is returned
 * in dedicated {@code thinking} content blocks that the client discards before
 * publishing, so only the review text itself is posted.</p>
 *
 * <p>Adaptive thinking is the format supported by Claude 5.x models (the
 * request carries {@code thinking.type=adaptive} plus an {@code output_config}
 * effort level; the legacy fixed {@code budget_tokens} form is not used).
 * Disabled by default. Enable via the
 * {@code anthropic.extended-thinking.enabled} property or the
 * {@code ANTHROPIC_EXTENDED_THINKING_ENABLED} environment variable (Docker).</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "anthropic.extended-thinking")
public class AnthropicExtendedThinkingProperties {

    /**
     * Master switch. Default {@code false} (opt-in).
     */
    private boolean enabled = false;

    /**
     * Soft steering for how often and how deeply the model thinks. One of
     * {@code low}, {@code medium}, {@code high}, {@code xhigh} or {@code max}.
     * Higher efforts need a correspondingly larger {@code max_tokens}.
     */
    private String effort = "high";
}
