package org.remus.giteabot.repository.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryUserTest {

    private static final RepositoryUser BOT = new RepositoryUser("17", "ai_bot", null, "bot@example.com");

    @Test
    void missingEmail_fallsBackToTheBotAddress() {
        assertThat(new RepositoryUser("17", "ai_bot", null, null).email()).isEqualTo("ai-agent@bot.local");
        assertThat(new RepositoryUser("17", "ai_bot", null, " ").email()).isEqualTo("ai-agent@bot.local");
    }

    @Test
    void matches_comparesTheId() {
        assertThat(BOT.matches(new RepositoryUser("17", "renamed_bot", null, "bot@example.com"))).isTrue();
        assertThat(BOT.matches(new RepositoryUser("99", "ai_bot", null, "bot@example.com"))).isFalse();
    }
}
