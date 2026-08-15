package org.remus.giteabot.issueworkflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Migration test for V42 (issue triage configuration seed): migrates a
 * scratch H2 database to 41, applies V42 and asserts the ready-made,
 * non-default 'Issue: Triage' configuration and its 'issue-triage' workflow
 * selection.
 *
 * <p>Standalone Flyway/H2 probe (the Spring test profile runs with Flyway
 * disabled), following {@link IssueWorkflowMigrationTest}.</p>
 */
class IssueTriageMigrationTest {

    private static final String URL = "jdbc:h2:mem:issue-triage-migration-test;DB_CLOSE_DELAY=-1";
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

    @Test
    void v42_seedsNonDefaultIssueTriageConfiguration() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
            flyway("41").migrate();
            flyway("42").migrate();

            long triageCfgId = q1(c, "SELECT id FROM workflow_configurations"
                    + " WHERE name='Issue: Triage' AND kind='ISSUE' AND default_entry=FALSE");
            assertEquals(1, q1(c, "SELECT COUNT(*) FROM workflow_selections"
                    + " WHERE workflow_configuration_id=" + triageCfgId
                    + " AND workflow_key='issue-triage'"));

            // Triage is opt-in: the ISSUE default is untouched and no params
            // rows are seeded (schema defaults apply on read).
            assertEquals(1, q1(c, "SELECT COUNT(*) FROM workflow_configurations"
                    + " WHERE kind='ISSUE' AND default_entry=TRUE AND name='Issue: Coding Agent'"));
            assertEquals(0, q1(c, "SELECT COUNT(*) FROM workflow_selection_params p"
                    + " JOIN workflow_selections s ON p.workflow_selection_id = s.id"
                    + " WHERE s.workflow_configuration_id=" + triageCfgId));
        }
    }
}
