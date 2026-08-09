package org.remus.giteabot.issueworkflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Migration test for V38/V39 (issue-assigned workflow configurations):
 * migrates a scratch H2 database to 37, inserts a CODING and a WRITER bot,
 * applies V38+V39 and asserts the seed rows and the per-bot reassignment.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway
 * disabled), following the recipe in
 * {@code doc/development-archive} flyway-data-migrations notes.</p>
 */
class IssueWorkflowMigrationTest {

    private static final String URL = "jdbc:h2:mem:issue-workflow-migration-test;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, "sa", "")
                .locations(LOCATIONS)
                .target(target)
                .load();
    }

    private static long q1(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("no row: " + sql);
            }
            return rs.getLong(1);
        }
    }

    private static String qs(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("no row: " + sql);
            }
            return rs.getString(1);
        }
    }

    private static void insertBot(Connection c, String name, String botType, long aiId, long gitId,
                                  long promptId, long toolCfgId, long wfCfgId) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO bots (name, username, system_prompt_id, bot_tool_configuration_id,"
                    + " workflow_configuration_id, enabled, ai_integration_id, git_integration_id,"
                    + " agent_enabled, run_on_pr_creation, run_on_pr_update, bot_type,"
                    + " webhook_call_count, ai_tokens_sent, ai_tokens_received, created_at, updated_at)"
                    + " VALUES ('" + name + "', '" + name + "-user', " + promptId + ", " + toolCfgId + ", "
                    + wfCfgId + ", TRUE, " + aiId + ", " + gitId + ", FALSE, FALSE, FALSE, '" + botType
                    + "', 0, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void v39_seedsIssueWorkflows_andMigratesExistingBotsOffBotType() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            flyway("37").migrate();

            try (Statement s = c.createStatement()) {
                s.execute("INSERT INTO ai_integrations (name, provider_type, api_url, model,"
                        + " created_at, updated_at) VALUES ('ai','OPENAI','http://x','m',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                s.execute("INSERT INTO git_integrations (name, provider_type, url, token,"
                        + " created_at, updated_at) VALUES ('git','GITEA','http://x','t',"
                        + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
            long aiId = q1(c, "SELECT id FROM ai_integrations WHERE name='ai'");
            long gitId = q1(c, "SELECT id FROM git_integrations WHERE name='git'");
            long promptId = q1(c, "SELECT id FROM system_prompts WHERE default_entry=TRUE");
            long toolCfgId = q1(c, "SELECT id FROM bot_tool_configurations WHERE default_entry=TRUE");
            long defaultWfId = q1(c, "SELECT id FROM workflow_configurations WHERE default_entry=TRUE");

            insertBot(c, "coding-bot", "CODING", aiId, gitId, promptId, toolCfgId, defaultWfId);
            insertBot(c, "writer-bot", "WRITER", aiId, gitId, promptId, toolCfgId, defaultWfId);

            flyway("39").migrate();

            // Schema: the pre-existing Default keeps PR semantics via the column default.
            assertEquals("PR", qs(c, "SELECT kind FROM workflow_configurations WHERE id=" + defaultWfId));

            // Seed: two ISSUE-kind configurations, exactly one default, plus the empty PR config.
            assertEquals(2, q1(c, "SELECT COUNT(*) FROM workflow_configurations WHERE kind='ISSUE'"));
            assertEquals(1, q1(c, "SELECT COUNT(*) FROM workflow_configurations"
                    + " WHERE kind='ISSUE' AND default_entry=TRUE"));
            long codingCfgId = q1(c, "SELECT id FROM workflow_configurations"
                    + " WHERE kind='ISSUE' AND default_entry=TRUE AND name='Issue: Coding Agent'");
            long writerCfgId = q1(c, "SELECT id FROM workflow_configurations"
                    + " WHERE name='Issue: Writer Agent'");
            long noPrCfgId = q1(c, "SELECT id FROM workflow_configurations"
                    + " WHERE name='No PR workflows' AND kind='PR'");
            assertEquals(1, q1(c, "SELECT COUNT(*) FROM workflow_selections"
                    + " WHERE workflow_configuration_id=" + codingCfgId + " AND workflow_key='issue-coding'"));
            assertEquals(1, q1(c, "SELECT COUNT(*) FROM workflow_selections"
                    + " WHERE workflow_configuration_id=" + writerCfgId + " AND workflow_key='issue-writer'"));
            assertEquals(0, q1(c, "SELECT COUNT(*) FROM workflow_selections"
                    + " WHERE workflow_configuration_id=" + noPrCfgId));

            // Data migration: both prior botType values land on the equivalent issue workflow.
            assertEquals(codingCfgId, q1(c, "SELECT issue_workflow_configuration_id"
                    + " FROM bots WHERE name='coding-bot'"));
            assertEquals(defaultWfId, q1(c, "SELECT workflow_configuration_id"
                    + " FROM bots WHERE name='coding-bot'"));
            assertEquals(writerCfgId, q1(c, "SELECT issue_workflow_configuration_id"
                    + " FROM bots WHERE name='writer-bot'"));
            assertEquals(noPrCfgId, q1(c, "SELECT workflow_configuration_id"
                    + " FROM bots WHERE name='writer-bot'"));
        }
    }
}
