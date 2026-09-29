package com.meonggo.backend.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import com.meonggo.backend.ingestion.repository.IngestionStatusRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class IngestionStatusApiTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final String SOURCE = "ANIMAL_PROTECTION_API";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @MockitoSpyBean private IngestionStatusRepository runs;
    @MockitoBean private LoginService login;

    @MockitoBean(name = "dataSourceClock")
    private Clock dataSourceClock;

    private String accessToken;

    @BeforeEach
    void setup() {
        jdbc.update("delete from ingestion_run");
        accessToken = "Bearer " + sessions.create(member()).accessToken();
        when(dataSourceClock.instant()).thenReturn(NOW);
    }

    @Test
    void reportsNeverSyncedWithZeroSnapshotAndRequiresAuthentication() throws Exception {
        statusRequest(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("공공데이터 갱신 상태를 조회했습니다."))
                .andExpect(jsonPath("$.data.sourceSystem").value(SOURCE))
                .andExpect(jsonPath("$.data.status").value("NEVER_SYNCED"))
                .andExpect(jsonPath("$.data.lastSuccessfulAt").doesNotExist())
                .andExpect(jsonPath("$.data.lastSourceUpdatedAt").doesNotExist())
                .andExpect(jsonPath("$.data.lastAttemptAt").doesNotExist())
                .andExpect(jsonPath("$.data.fetchedCount").value(0))
                .andExpect(jsonPath("$.data.insertedCount").value(0))
                .andExpect(jsonPath("$.data.updatedCount").value(0))
                .andExpect(jsonPath("$.data.shelterCount").value(0))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        statusRequest(null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
    }

    @Test
    void initialFullIsTheOperationalAttemptBeforeTheFirstDailyRun() throws Exception {
        Instant completed = NOW.minusSeconds(3600);
        Instant started = completed.minusSeconds(600);
        insert(
                "INITIAL_FULL",
                "SUCCEEDED",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 9),
                10,
                10,
                0,
                2,
                0,
                completed.minusSeconds(60),
                started,
                completed,
                null);

        statusRequest(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.lastAttemptAt").value(started.toString()))
                .andExpect(jsonPath("$.data.lastSuccessfulAt").value(completed.toString()));
    }

    @Test
    void failedAttemptWithoutAnySuccessIsStillReportedAsFailed() throws Exception {
        Instant started = NOW.minusSeconds(600);
        insert(
                "DAILY_INCREMENTAL",
                "FAILED",
                LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 10),
                3,
                0,
                0,
                0,
                3,
                null,
                started,
                NOW.minusSeconds(300),
                "private-failure");

        statusRequest(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.lastSuccessfulAt").doesNotExist())
                .andExpect(jsonPath("$.data.fetchedCount").value(0));
    }

    @Test
    void runningAttemptUsesLastSuccessfulInitialSnapshotAndIgnoresBackfill() throws Exception {
        Instant initialCompleted = NOW.minusSeconds(50 * 60 * 60);
        Instant sourceUpdated = initialCompleted.minusSeconds(600);
        insert(
                "INITIAL_FULL",
                "SUCCEEDED",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 7),
                100,
                10,
                20,
                3,
                1,
                sourceUpdated,
                initialCompleted.minusSeconds(3600),
                initialCompleted,
                null);
        insert(
                "BACKFILL",
                "FAILED",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2),
                999,
                999,
                999,
                999,
                999,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(1800),
                NOW.minusSeconds(1200),
                "backfill-private-error");
        Instant runningStarted = NOW.minusSeconds(300);
        insert(
                "DAILY_INCREMENTAL",
                "RUNNING",
                LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 10),
                5,
                1,
                2,
                1,
                0,
                null,
                runningStarted,
                null,
                null);

        statusRequest(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.lastAttemptAt").value(runningStarted.toString()))
                .andExpect(jsonPath("$.data.lastSuccessfulAt").value(initialCompleted.toString()))
                .andExpect(jsonPath("$.data.lastSourceUpdatedAt").value(sourceUpdated.toString()))
                .andExpect(jsonPath("$.data.fetchedCount").value(100))
                .andExpect(jsonPath("$.data.insertedCount").value(10))
                .andExpect(jsonPath("$.data.updatedCount").value(20))
                .andExpect(jsonPath("$.data.shelterCount").value(3))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "backfill-private-error"))));
    }

    @Test
    void failedAttemptKeepsTheLatestSuccessfulDailySnapshot() throws Exception {
        Instant successCompleted = NOW.minusSeconds(7200);
        insert(
                "DAILY_INCREMENTAL",
                "SUCCEEDED",
                LocalDate.of(2026, 9, 8),
                LocalDate.of(2026, 9, 9),
                120,
                37,
                84,
                12,
                0,
                NOW.minusSeconds(7500),
                NOW.minusSeconds(8000),
                successCompleted,
                null);
        Instant failedStarted = NOW.minusSeconds(1800);
        insert(
                "DAILY_INCREMENTAL",
                "FAILED",
                LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 10),
                7,
                1,
                1,
                1,
                4,
                null,
                failedStarted,
                NOW.minusSeconds(1200),
                "collector-private-error");

        statusRequest(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.lastAttemptAt").value(failedStarted.toString()))
                .andExpect(jsonPath("$.data.lastSuccessfulAt").value(successCompleted.toString()))
                .andExpect(jsonPath("$.data.insertedCount").value(37))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "collector-private-error"))));
    }

    @Test
    void appliesTheThirtySixHourDelayBoundaryToTheLatestSuccess() throws Exception {
        long delayed =
                insert(
                        "DAILY_INCREMENTAL",
                        "SUCCEEDED",
                        LocalDate.of(2026, 9, 7),
                        LocalDate.of(2026, 9, 8),
                        1,
                        1,
                        1,
                        1,
                        0,
                        null,
                        NOW.minusSeconds(36 * 60 * 60 + 2),
                        NOW.minusSeconds(36 * 60 * 60 + 1),
                        null);
        statusRequest(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELAYED"));

        jdbc.update("delete from ingestion_run where id=?", delayed);
        insert(
                "DAILY_INCREMENTAL",
                "SUCCEEDED",
                LocalDate.of(2026, 9, 8),
                LocalDate.of(2026, 9, 9),
                1,
                1,
                1,
                1,
                0,
                null,
                NOW.minusSeconds(36 * 60 * 60 + 1),
                NOW.minusSeconds(36 * 60 * 60),
                null);
        statusRequest(accessToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void publicSummaryReturnsLatestSuccessfulDailyIncludingZeroAnimals() throws Exception {
        insert(
                "INITIAL_FULL",
                "SUCCEEDED",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 9, 1),
                1000,
                1000,
                100,
                50,
                0,
                null,
                NOW.minusSeconds(10000),
                NOW.minusSeconds(9000),
                null);
        insert(
                "BACKFILL",
                "SUCCEEDED",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 2),
                100,
                100,
                10,
                10,
                0,
                null,
                NOW.minusSeconds(8000),
                NOW.minusSeconds(7000),
                null);
        insert(
                "DAILY_INCREMENTAL",
                "FAILED",
                LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 10),
                20,
                20,
                2,
                2,
                1,
                null,
                NOW.minusSeconds(600),
                NOW.minusSeconds(500),
                "do-not-expose-this-error");
        insert(
                "DAILY_INCREMENTAL",
                "SUCCEEDED",
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 8),
                99,
                99,
                9,
                9,
                0,
                NOW.minusSeconds(4100),
                NOW.minusSeconds(5100),
                NOW.minusSeconds(3600),
                null);
        long summaryId =
                insert(
                        "DAILY_INCREMENTAL",
                        "SUCCEEDED",
                        LocalDate.of(2026, 9, 8),
                        LocalDate.of(2026, 9, 9),
                        12,
                        0,
                        4,
                        0,
                        0,
                        NOW.minusSeconds(4000),
                        NOW.minusSeconds(5000),
                        NOW.minusSeconds(3600),
                        null);

        summary()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("일일 입소 요약을 조회했습니다."))
                .andExpect(jsonPath("$.data.summary.ingestionRunId").value(summaryId))
                .andExpect(jsonPath("$.data.summary.summaryDate").value("2026-09-09"))
                .andExpect(jsonPath("$.data.summary.animalCount").value(0))
                .andExpect(jsonPath("$.data.summary.shelterCount").value(0))
                .andExpect(
                        jsonPath("$.data.summary.completedAt")
                                .value(NOW.minusSeconds(3600).toString()))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "do-not-expose-this-error"))));
    }

    @Test
    void summaryFallsBackToTheKoreanCompletionDateWhenRequestDateIsMissing() throws Exception {
        Instant completed = Instant.parse("2026-09-09T16:00:00Z");
        insert(
                "DAILY_INCREMENTAL",
                "SUCCEEDED",
                null,
                null,
                4,
                2,
                1,
                1,
                0,
                null,
                completed.minusSeconds(300),
                completed,
                null);

        summary()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.summaryDate").value("2026-09-10"));
    }

    @Test
    void databaseFailureIsTraceableWhileTheResponseStaysGeneric(CapturedOutput output)
            throws Exception {
        String marker = "ingestion-status-db-failure";
        doThrow(new DataAccessResourceFailureException(marker))
                .when(runs)
                .latestOperationalAttempt(SOURCE);

        statusRequest(accessToken)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON-500"))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(marker))));
        assertThat(output.getAll()).contains("DataAccessResourceFailureException").contains(marker);
    }

    @Test
    void publicSummaryReturnsExplicitNullBeforeTheFirstSuccessfulDailyRun() throws Exception {
        insert(
                "DAILY_INCREMENTAL",
                "FAILED",
                LocalDate.of(2026, 9, 9),
                LocalDate.of(2026, 9, 10),
                1,
                0,
                0,
                0,
                1,
                null,
                NOW.minusSeconds(200),
                NOW.minusSeconds(100),
                null);

        summary()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value(org.hamcrest.Matchers.nullValue()));
    }

    private ResultActions statusRequest(String token) throws Exception {
        var request = get("/api/v1/data-sources/shelter-animals/status");
        if (token != null) request.header("Authorization", token);
        return mvc.perform(request.accept(MediaType.APPLICATION_JSON));
    }

    private ResultActions summary() throws Exception {
        return mvc.perform(
                get("/api/v1/data-sources/shelter-animals/daily-summary")
                        .accept(MediaType.APPLICATION_JSON));
    }

    private long insert(
            String runType,
            String status,
            LocalDate from,
            LocalDate to,
            int fetched,
            int inserted,
            int updated,
            int shelters,
            int failed,
            Instant sourceUpdated,
            Instant started,
            Instant completed,
            String error) {
        return jdbc.queryForObject(
                """
                insert into ingestion_run(
                    source_system,run_type,status,requested_from_date,requested_to_date,
                    fetched_count,inserted_count,updated_count,shelter_count,failed_count,
                    last_source_updated_at,error_summary,started_at,completed_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id
                """,
                Long.class,
                SOURCE,
                runType,
                status,
                from,
                to,
                fetched,
                inserted,
                updated,
                shelters,
                failed,
                timestamp(sourceUpdated),
                error,
                timestamp(started),
                timestamp(completed));
    }

    private java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private long member() {
        return jdbc.queryForObject(
                """
                insert into member(
                    login_id,password_hash,nickname,status,created_at,updated_at,
                    phone_ciphertext,phone_lookup_hash,phone_verified_at,
                    privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                values(?,'fixture','상태 조회자','ACTIVE',now(),now(),'private-phone',?,now(),
                       true, 'privacy-collection-v1',now()) returning id
                """,
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }
}
