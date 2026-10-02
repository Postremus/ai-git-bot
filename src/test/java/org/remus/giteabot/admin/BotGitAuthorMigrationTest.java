package org.remus.giteabot.admin;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.repository.model.GitAuthor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration gate for the {@code bots.git_author_name} / {@code bots.git_author_email}
 * columns: the entity maps them as non-null, so a bot created before the setting
 * existed must come out of the migration with the identity its commits used
 * before ({@link GitAuthor#DEFAULT}), not with a blank or missing author.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway
 * disabled). It migrates to the previous version, persists a legacy bot, then
 * migrates to V56 and asserts the bot received the default identity.</p>
 */
class BotGitAuthorMigrationTest {

    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";

    private static Flyway flyway(String url, Integer target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(LOCATIONS);
        if (target != null) {
            configuration = configuration.target(target.toString());
        }
        return configuration.load();
    }

    @Test
    void migrationAssignsTheDefaultIdentityToExistingBots() throws Exception {
        String url = "jdbc:h2:mem:bot-git-author-upgrade;DB_CLOSE_DELAY=-1";
        flyway(url, 55).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO ai_integrations "
                    + "(name, provider_type, api_url, model, created_at, updated_at) VALUES "
                    + "('legacy-ai', 'anthropic', 'https://api.anthropic.com', 'claude-sonnet-4', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO git_integrations (name, provider_type, url, created_at, updated_at) "
                    + "VALUES ('legacy-git', 'GITEA', 'https://gitea.example.com', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO bots (name, username, system_prompt_id, bot_tool_configuration_id,"
                    + " ai_integration_id, git_integration_id, created_at, updated_at) VALUES"
                    + " ('legacy', 'legacy-user',"
                    + " (SELECT MIN(id) FROM system_prompts),"
                    + " (SELECT MIN(id) FROM bot_tool_configurations),"
                    + " (SELECT id FROM ai_integrations WHERE name = 'legacy-ai'),"
                    + " (SELECT id FROM git_integrations WHERE name = 'legacy-git'),"
                    + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        flyway(url, 56).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT git_author_name, git_author_email FROM bots WHERE name = 'legacy'")) {
            assertTrue(result.next(), "the legacy bot must still exist");
            assertEquals(GitAuthor.DEFAULT_NAME, result.getString(1));
            assertEquals(GitAuthor.DEFAULT_EMAIL, result.getString(2));
        }
    }

    @Test
    void columnsAreNotNullableAfterTheMigration() throws Exception {
        String url = "jdbc:h2:mem:bot-git-author-schema;DB_CLOSE_DELAY=-1";
        flyway(url, null).migrate();

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT column_name, is_nullable FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE TABLE_NAME = 'BOTS' "
                             + "AND COLUMN_NAME IN ('GIT_AUTHOR_NAME', 'GIT_AUTHOR_EMAIL') "
                             + "ORDER BY column_name")) {
            assertTrue(result.next(), "the git_author_email column must exist");
            assertEquals("GIT_AUTHOR_EMAIL", result.getString(1));
            assertEquals("NO", result.getString(2));
            assertTrue(result.next(), "the git_author_name column must exist");
            assertEquals("GIT_AUTHOR_NAME", result.getString(1));
            assertEquals("NO", result.getString(2));
        }
    }
}
