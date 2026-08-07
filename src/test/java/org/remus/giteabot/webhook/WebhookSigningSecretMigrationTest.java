package org.remus.giteabot.webhook;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebhookSigningSecretMigrationTest {

    private static final String NORMAL_UPGRADE_URL =
            "jdbc:h2:mem:webhook-signing-secret-normal-upgrade-test;DB_CLOSE_DELAY=-1";
    private static final String EXISTING_COLUMN_URL =
            "jdbc:h2:mem:webhook-signing-secret-existing-column-test;DB_CLOSE_DELAY=-1";
    private static final String LOCATIONS = "filesystem:src/main/resources/db/migration/h2";

    private static Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(LOCATIONS)
                .target(target)
                .load();
    }

    @Test
    void v40_addsTheSigningSecretColumn() throws Exception {
        flyway(NORMAL_UPGRADE_URL, "39").migrate();
        flyway(NORMAL_UPGRADE_URL, "40").migrate();

        assertSigningSecretColumn(NORMAL_UPGRADE_URL);
    }

    @Test
    void v40_acceptsAnExistingSigningSecretColumn() throws Exception {
        flyway(EXISTING_COLUMN_URL, "39").migrate();

        try (Connection connection = DriverManager.getConnection(EXISTING_COLUMN_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE bots ADD COLUMN webhook_signing_secret VARCHAR(1000)");
        }

        flyway(EXISTING_COLUMN_URL, "40").migrate();

        assertSigningSecretColumn(EXISTING_COLUMN_URL);
    }

    private static void assertSigningSecretColumn(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                     + "WHERE TABLE_NAME = 'BOTS' AND COLUMN_NAME = 'WEBHOOK_SIGNING_SECRET'")) {
            result.next();
            assertEquals(1, result.getInt(1));
        }
    }
}
