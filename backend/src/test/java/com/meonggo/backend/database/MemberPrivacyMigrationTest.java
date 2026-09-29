package com.meonggo.backend.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class MemberPrivacyMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void versionNineBackfillsRetainedConsentWithoutRecreatingErasedRecords() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(
                            """
                            CREATE TEMP TABLE member (
                                id bigint PRIMARY KEY,
                                status varchar(20) NOT NULL,
                                privacy_collection_policy_version varchar(50),
                                privacy_collection_consented_at timestamptz,
                                personal_data_erased_at timestamptz
                            )
                            """);
                    statement.execute(
                            """
                            INSERT INTO member (
                                id, status, privacy_collection_policy_version,
                                privacy_collection_consented_at, personal_data_erased_at
                            ) VALUES
                                (1, 'ACTIVE', 'privacy-collection-v1', '2026-09-01T00:00:00Z', NULL),
                                (2, 'WITHDRAWN', 'privacy-collection-v1', '2026-09-01T00:00:00Z', NULL),
                                (3, 'WITHDRAWN', 'privacy-collection-v1',
                                    '2026-08-01T00:00:00Z', '2026-09-01T00:00:00Z'),
                                (4, 'WITHDRAWN', NULL, NULL, '2026-09-01T00:00:00Z')
                            """);
                }

                ScriptUtils.executeSqlScript(
                        connection,
                        new ClassPathResource(
                                "db/migration/V9__member_privacy_collection_agreement.sql"));

                try (Statement statement = connection.createStatement();
                        ResultSet rows =
                                statement.executeQuery(
                                        "SELECT id, privacy_collection_agreed, "
                                                + "privacy_collection_policy_version, "
                                                + "privacy_collection_consented_at "
                                                + "FROM member ORDER BY id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(1);
                    assertThat(rows.getBoolean("privacy_collection_agreed")).isTrue();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(2);
                    assertThat(rows.getBoolean("privacy_collection_agreed")).isTrue();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(3);
                    assertThat(rows.getObject("privacy_collection_agreed")).isNull();
                    assertThat(rows.getString("privacy_collection_policy_version")).isNull();
                    assertThat(rows.getTimestamp("privacy_collection_consented_at")).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("id")).isEqualTo(4);
                    assertThat(rows.getObject("privacy_collection_agreed")).isNull();
                    assertThat(rows.next()).isFalse();
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
