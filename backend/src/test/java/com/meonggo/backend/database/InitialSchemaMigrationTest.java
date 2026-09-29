package com.meonggo.backend.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class InitialSchemaMigrationTest {

    private static final Set<String> APPROVED_TABLES =
            Set.of(
                    "member",
                    "auth_session",
                    "animal_case",
                    "animal_case_location",
                    "user_post",
                    "chat_room",
                    "chat_message",
                    "chat_notification_outbox",
                    "shelter",
                    "shelter_animal",
                    "lost_report",
                    "animal_photo",
                    "member_photo_erasure_task",
                    "match_run",
                    "match_candidate",
                    "ingestion_run",
                    "dashboard_stat");

    private static final Set<String> APPROVED_CONSTRAINTS =
            Set.of(
                    "uk_auth_session_refresh_token_selector",
                    "uk_auth_session_refresh_token_hash",
                    "uk_auth_session_push_installation",
                    "uk_auth_session_push_token_lookup_hash",
                    "uk_chat_notification_outbox_message",
                    "uk_chat_room_case_requester",
                    "uk_shelter_care_reg_no",
                    "uk_shelter_animal_desertion_no",
                    "uk_lost_report_lost_key",
                    "ck_lost_report_lost_key",
                    "ck_lost_report_seen_dates",
                    "uk_animal_photo_case_sort_order",
                    "uk_match_candidate_run_target",
                    "uk_match_candidate_run_rank",
                    "fk_auth_session_member",
                    "fk_user_post_member",
                    "fk_animal_case_location_animal_case",
                    "fk_user_post_animal_case",
                    "fk_chat_room_animal_case",
                    "fk_chat_room_owner_member",
                    "fk_chat_room_requester_member",
                    "fk_chat_message_chat_room",
                    "fk_chat_message_sender_member",
                    "fk_chat_notification_outbox_message",
                    "fk_chat_notification_outbox_recipient",
                    "fk_shelter_animal_animal_case",
                    "fk_animal_photo_animal_case",
                    "fk_shelter_animal_shelter",
                    "fk_shelter_animal_ingestion_run",
                    "fk_lost_report_animal_case",
                    "fk_lost_report_ingestion_run",
                    "fk_match_run_animal_case",
                    "fk_match_candidate_match_run",
                    "fk_match_candidate_animal_case",
                    "ck_member_status",
                    "ck_member_active_phone_policy",
                    "ck_member_active_privacy_collection_agreed",
                    "ck_member_erased_privacy_collection",
                    "ck_auth_session_expiry",
                    "ck_auth_session_revoked_at",
                    "ck_auth_session_push_registration",
                    "ck_animal_case_type",
                    "ck_animal_case_source",
                    "ck_animal_case_source_type_pair",
                    "ck_animal_case_status",
                    "ck_animal_case_status_dates",
                    "ck_animal_case_species",
                    "ck_animal_case_sex",
                    "ck_animal_case_version",
                    "ck_animal_case_location_type",
                    "ck_animal_case_location_pair",
                    "ck_animal_case_location_region_code",
                    "ck_animal_case_location_emd_code",
                    "ck_animal_case_latitude",
                    "ck_animal_case_longitude",
                    "ck_animal_case_location_disclosure",
                    "ck_user_post_request_hash",
                    "ck_user_post_close_reason",
                    "ck_chat_room_participants",
                    "ck_chat_room_owner_read_position",
                    "ck_chat_room_requester_read_position",
                    "ck_chat_message_content",
                    "ck_chat_message_request_hash",
                    "ck_chat_notification_outbox_status",
                    "ck_chat_notification_outbox_attempt_count",
                    "ck_chat_notification_outbox_completed_at",
                    "ck_shelter_animal_notice_dates",
                    "ck_shelter_animal_neuter_status",
                    "ck_animal_photo_storage_type",
                    "ck_animal_photo_sort_order",
                    "ck_animal_photo_user_metadata",
                    "ck_match_run_status",
                    "ck_match_run_candidate_count",
                    "ck_match_run_timestamps",
                    "ck_match_run_error",
                    "ck_match_candidate_rank",
                    "ck_match_candidate_total_score",
                    "ck_match_candidate_image_score",
                    "ck_match_candidate_distance",
                    "ck_match_candidate_time_gap",
                    "ck_ingestion_run_status",
                    "ck_ingestion_run_type",
                    "ck_ingestion_run_counts",
                    "ck_ingestion_run_request_dates",
                    "ck_ingestion_run_completed_at",
                    "ck_member_erasure_progress",
                    "pk_member_photo_erasure_task",
                    "uk_member_photo_erasure_task_storage_uri",
                    "ck_member_photo_erasure_task_path",
                    "ck_member_photo_erasure_task_attempt_count",
                    "ck_member_photo_erasure_task_error",
                    "ck_member_photo_erasure_task_timestamps");

    private static final Set<String> APPROVED_INDEXES =
            Set.of(
                    "idx_animal_case_list",
                    "idx_animal_case_list_filter",
                    "idx_animal_case_location_public",
                    "idx_member_login_id_canonical",
                    "idx_member_active_phone_lookup_hash",
                    "idx_member_data_erasure_due",
                    "idx_member_photo_erasure_task_due",
                    "idx_auth_session_member_active",
                    "idx_auth_session_push_recipient",
                    "idx_user_post_member",
                    "uk_user_post_member_client_request",
                    "idx_chat_room_owner_latest",
                    "idx_chat_room_requester_latest",
                    "idx_chat_message_room_cursor",
                    "idx_chat_message_room_after",
                    "idx_chat_message_room_sender_id",
                    "uk_chat_message_sender_client_message",
                    "idx_chat_notification_outbox_due",
                    "idx_animal_case_matchable",
                    "idx_shelter_animal_shelter",
                    "idx_match_run_latest_success",
                    "idx_match_run_active_query",
                    "idx_match_candidate_run_rank",
                    "idx_match_candidate_target",
                    "idx_ingestion_run_latest_success");

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesVersionOneAndCreatesApprovedTables() {
        List<String> tables =
                jdbcTemplate.queryForList(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public'",
                        String.class);

        assertThat(tables).containsAll(APPROVED_TABLES);

        Integer successfulV1 =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history "
                                + "WHERE version = '1' AND success",
                        Integer.class);
        assertThat(successfulV1).isEqualTo(1);
    }

    @Test
    void schemaContainsApprovedConstraintsAndIndexes() {
        List<String> constraints =
                jdbcTemplate.queryForList(
                        "SELECT conname FROM pg_constraint "
                                + "WHERE connamespace = 'public'::regnamespace",
                        String.class);
        List<String> indexes =
                jdbcTemplate.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                        String.class);

        assertThat(constraints).containsAll(APPROVED_CONSTRAINTS);
        assertThat(indexes).containsAll(APPROVED_INDEXES);
    }

    @Test
    void versionFiveAddsMemberErasureProgressColumns() {
        List<String> columns =
                jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='member'",
                        String.class);
        Integer successfulV5 =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history "
                                + "WHERE version = '5' AND success",
                        Integer.class);

        assertThat(columns).contains("personal_data_erased_at", "relational_data_erased_at");
        assertThat(successfulV5).isEqualTo(1);
    }

    @Test
    void versionSixAddsMemberPhotoErasureOutbox() {
        List<String> columns =
                jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema='public' "
                                + "AND table_name='member_photo_erasure_task'",
                        String.class);
        Integer successfulV6 =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history "
                                + "WHERE version = '6' AND success",
                        Integer.class);

        assertThat(columns)
                .contains(
                        "storage_uri",
                        "deadline_at",
                        "attempt_count",
                        "next_attempt_at",
                        "lease_until",
                        "last_error_code");
        assertThat(successfulV6).isEqualTo(1);
    }

    @Test
    void versionEightAddsChatReadPositions() {
        List<String> columns =
                jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema='public' AND table_name='chat_room'",
                        String.class);
        Integer successfulV8 =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history "
                                + "WHERE version = '8' AND success",
                        Integer.class);

        assertThat(columns)
                .contains("owner_last_read_message_id", "requester_last_read_message_id");
        assertThat(successfulV8).isEqualTo(1);
    }

    @Test
    void versionNineBackfillsAndConstrainsMemberPrivacyAgreement() {
        Integer successfulV9 =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version='9' AND success",
                        Integer.class);
        Integer activeWithoutAgreement =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM member "
                                + "WHERE status='ACTIVE' AND privacy_collection_agreed IS DISTINCT FROM true",
                        Integer.class);
        Integer erasedWithAgreement =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM member "
                                + "WHERE personal_data_erased_at IS NOT NULL "
                                + "AND privacy_collection_agreed IS NOT NULL",
                        Integer.class);

        assertThat(successfulV9).isEqualTo(1);
        assertThat(activeWithoutAgreement).isZero();
        assertThat(erasedWithAgreement).isZero();
    }

    @Test
    void activeMemberRejectsMissingOrFalsePrivacyAgreement() {
        String identifier = UUID.randomUUID().toString().replace("-", "");
        String loginId = "privacy-agreement-" + identifier;
        insertActiveMember(loginId, identifier + identifier);

        assertPostgresState(
                "23514",
                () ->
                        jdbcTemplate.update(
                                "UPDATE member SET privacy_collection_agreed=false WHERE login_id=?",
                                loginId));
        assertPostgresState(
                "23514",
                () ->
                        jdbcTemplate.update(
                                "UPDATE member SET privacy_collection_agreed=null WHERE login_id=?",
                                loginId));
    }

    @Test
    void memberPhotoErasureOutboxRejectsPathsOutsideUserPhotoRoot() {
        assertPostgresState(
                "23514",
                () ->
                        jdbcTemplate.update(
                                """
                                insert into member_photo_erasure_task(
                                  storage_uri,deadline_at,next_attempt_at,created_at,updated_at)
                                values ('/data/shelter/images/private.jpg',now(),now(),now(),now())
                                """));
    }

    @Test
    void memberErasureProgressRejectsOutOfOrderCompletion() {
        String identifier = UUID.randomUUID().toString().replace("-", "");
        insertActiveMember("erasure-progress-" + identifier, identifier + identifier);

        assertPostgresState(
                "23514",
                () ->
                        jdbcTemplate.update(
                                "UPDATE member SET relational_data_erased_at=now() "
                                        + "WHERE login_id=?",
                                "erasure-progress-" + identifier));
    }

    @Test
    void animalCaseRejectsUnknownCaseType() {
        assertPostgresState(
                "23514",
                () ->
                        jdbcTemplate.update(
                                """
                                INSERT INTO animal_case (
                                    case_type, source_type, listed_at, species, sex,
                                    event_date, created_at, updated_at
                                ) VALUES (
                                    'UNKNOWN', 'USER', now(), 'DOG', 'UNKNOWN',
                                    current_date, now(), now()
                                )
                                """));
    }

    @Test
    void activePhoneLookupHashIsUnique() {
        String identifier = UUID.randomUUID().toString().replace("-", "");
        String phoneLookupHash = identifier + identifier;

        assertPostgresState(
                "23505",
                () -> {
                    insertActiveMember("first-" + identifier, phoneLookupHash);
                    insertActiveMember("second-" + identifier, phoneLookupHash);
                });
    }

    @Test
    void authSessionRejectsMissingMember() {
        String identifier = UUID.randomUUID().toString().replace("-", "");

        assertPostgresState(
                "23503",
                () ->
                        jdbcTemplate.update(
                                """
                                INSERT INTO auth_session (
                                    member_id, refresh_token_selector, refresh_token_hash,
                                    expires_at, created_at
                                ) VALUES (?, ?, ?, now() + interval '1 day', now())
                                """,
                                Long.MAX_VALUE,
                                identifier.substring(0, 22),
                                identifier + identifier));
    }

    private void insertActiveMember(String loginId, String phoneLookupHash) {
        jdbcTemplate.update(
                """
                INSERT INTO member (
                    login_id, password_hash, nickname, phone_ciphertext,
                    phone_lookup_hash, phone_verified_at,
                    privacy_collection_agreed, privacy_collection_policy_version,
                    privacy_collection_consented_at, created_at, updated_at
                ) VALUES (?, '{argon2id-v1}test', 'tester', 'enc:v1:test', ?,
                          now(), true, 'privacy-collection-v1', now(), now(), now())
                """,
                loginId,
                phoneLookupHash);
    }

    private void assertPostgresState(String expectedState, Runnable databaseOperation) {
        assertThatThrownBy(databaseOperation::run)
                .satisfies(
                        exception ->
                                assertThat(postgresCause(exception).getSQLState())
                                        .isEqualTo(expectedState));
    }

    private SQLException postgresCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        throw new AssertionError("Expected PostgreSQL failure", throwable);
    }
}
