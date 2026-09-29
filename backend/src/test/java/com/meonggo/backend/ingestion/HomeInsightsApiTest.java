package com.meonggo.backend.ingestion;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** D3 홈 인사이트 (docs/api-spec.md). "오늘"은 dataSourceClock 의 KST 날짜 — 2026-09-10. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HomeInsightsApiTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 10);
    private static final String REGION = "11440";
    private static final String OTHER_REGION = "26440";
    private static final String PATH = "/api/v1/data-sources/shelter-animals/insights";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean(name = "dataSourceClock")
    private Clock dataSourceClock;

    private long shelterId;

    @BeforeEach
    void setup() {
        when(dataSourceClock.instant()).thenReturn(NOW);
        jdbc.update("delete from dashboard_stat");
        jdbc.update("delete from ingestion_run");
        jdbc.update(
                "delete from animal_case where source_type='PUBLIC' and id in (select animal_case_id from animal_case_location where region_code in (?,?,?))",
                REGION,
                OTHER_REGION,
                "11110");
        jdbc.update("delete from shelter where care_reg_no='insights-test-shelter'");
        shelterId =
                jdbc.queryForObject(
                        "insert into shelter(care_reg_no,name,created_at,updated_at) values('insights-test-shelter','인사이트 보호소',now(),now()) returning id",
                        Long.class);
    }

    @Test
    void withoutRegionThePiecesAreNationwideAndOptionalOnesStayExplicitlyNull() throws Exception {
        lost(TODAY.minusDays(1), REGION, "DOG");
        sheltering(TODAY.minusDays(1), OTHER_REGION, "CAT", "ACTIVE", TODAY.plusDays(1));
        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("홈 인사이트를 조회했습니다."))
                .andExpect(jsonPath("$.data.dailyIntake").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(
                        jsonPath("$.data.noticeClosing.regionCode")
                                .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.noticeClosing.animalCount").value(1))
                .andExpect(jsonPath("$.data.weeklyIntake.total").value(1))
                .andExpect(
                        jsonPath("$.data.shelterOutcomes").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.lostReports.yesterday").value("2026-09-09"))
                .andExpect(jsonPath("$.data.lostReports.yesterdayCount").value(1))
                .andExpect(
                        jsonPath("$.data.lostReports.regionCode")
                                .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.lostReports.regionLast30DaysCount").value(1))
                .andExpect(jsonPath("$.data.lostReports.regionDogCount").value(1));
        mvc.perform(get(PATH).param("regionCode", "1144"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
    }

    @Test
    void twoDigitRegionCodeCoversTheWholeProvince() throws Exception {
        // 11440(마포)와 11110(종로)은 서울(11) 안, 26440(부산 강서)은 밖.
        sheltering(TODAY.minusDays(1), REGION, "DOG", "ACTIVE", TODAY.plusDays(1));
        sheltering(TODAY.minusDays(1), "11110", "CAT", "ACTIVE", TODAY.plusDays(1));
        sheltering(TODAY.minusDays(1), OTHER_REGION, "DOG", "ACTIVE", TODAY.plusDays(1));
        lost(TODAY.minusDays(1), "11110", "CAT");
        lost(TODAY.minusDays(1), OTHER_REGION, "DOG");
        mvc.perform(get(PATH).param("regionCode", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noticeClosing.regionCode").value("11"))
                .andExpect(jsonPath("$.data.noticeClosing.animalCount").value(2))
                .andExpect(jsonPath("$.data.weeklyIntake.total").value(2))
                .andExpect(jsonPath("$.data.lostReports.regionLast30DaysCount").value(1))
                .andExpect(jsonPath("$.data.lostReports.regionCatCount").value(1));
    }

    @Test
    void regionPiecesCountOnlyTheSelectedRegionInsideTheirWindows() throws Exception {
        // 공고 마감: 오늘~+3일 안이고 ACTIVE 인 것만. 4일 뒤·CLOSED·다른 지역은 제외.
        sheltering(TODAY.minusDays(1), REGION, "DOG", "ACTIVE", TODAY.plusDays(2));
        sheltering(TODAY.minusDays(1), REGION, "CAT", "ACTIVE", TODAY);
        sheltering(TODAY.minusDays(1), REGION, "DOG", "ACTIVE", TODAY.plusDays(4));
        sheltering(TODAY.minusDays(1), REGION, "DOG", "CLOSED", TODAY.plusDays(1));
        sheltering(TODAY.minusDays(1), OTHER_REGION, "DOG", "ACTIVE", TODAY.plusDays(1));
        // 주간 입소: 7일 창(9/4~9/10). 9/3 은 제외.
        sheltering(TODAY.minusDays(6), REGION, "CAT", "ACTIVE", null);
        sheltering(TODAY.minusDays(7), REGION, "DOG", "ACTIVE", null);
        // 실종: 어제 처음 본 신고 2건(전국), 지역 30일 창 안 개 1·고양이 1, 31일 전은 제외.
        lost(TODAY.minusDays(1), REGION, "DOG");
        lost(TODAY.minusDays(1), OTHER_REGION, "CAT");
        lostSeen(lost(TODAY.minusDays(10), REGION, "CAT"), TODAY.minusDays(10));
        lostSeen(lost(TODAY.minusDays(31), REGION, "DOG"), TODAY.minusDays(31));
        jdbc.update(
                """
                insert into dashboard_stat(stat_key,region_code,payload,computed_at) values('shelter_outcomes','00000',
                  '{"windowStart":"2023-07-01","windowEnd":"2026-07-11","closedCount":320000,"returnRate":0.112,"adoptionRate":0.281,"averageNoticeDays":10.4}'::jsonb,
                  '2026-09-09T15:00:00Z')
                """);

        mvc.perform(get(PATH).param("regionCode", REGION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noticeClosing.regionCode").value(REGION))
                .andExpect(jsonPath("$.data.noticeClosing.withinDays").value(3))
                .andExpect(jsonPath("$.data.noticeClosing.animalCount").value(2))
                .andExpect(jsonPath("$.data.weeklyIntake.from").value("2026-09-04"))
                .andExpect(jsonPath("$.data.weeklyIntake.to").value("2026-09-10"))
                .andExpect(jsonPath("$.data.weeklyIntake.days.length()").value(7))
                .andExpect(jsonPath("$.data.weeklyIntake.days[0].date").value("2026-09-04"))
                .andExpect(jsonPath("$.data.weeklyIntake.days[0].catCount").value(1))
                .andExpect(jsonPath("$.data.weeklyIntake.days[5].date").value("2026-09-09"))
                .andExpect(jsonPath("$.data.weeklyIntake.days[5].dogCount").value(3))
                .andExpect(jsonPath("$.data.weeklyIntake.days[5].catCount").value(1))
                .andExpect(jsonPath("$.data.weeklyIntake.total").value(5))
                .andExpect(jsonPath("$.data.shelterOutcomes.returnRate").value(0.112))
                .andExpect(jsonPath("$.data.shelterOutcomes.closedCount").value(320000))
                .andExpect(jsonPath("$.data.shelterOutcomes.averageNoticeDays").value(10.4))
                .andExpect(
                        jsonPath("$.data.shelterOutcomes.computedAt").value("2026-09-09T15:00:00Z"))
                .andExpect(jsonPath("$.data.lostReports.yesterdayCount").value(2))
                .andExpect(jsonPath("$.data.lostReports.regionLast30DaysCount").value(2))
                .andExpect(jsonPath("$.data.lostReports.regionDogCount").value(1))
                .andExpect(jsonPath("$.data.lostReports.regionCatCount").value(1));
    }

    @Test
    void unreadableOutcomePayloadBlanksOnlyThatCard() throws Exception {
        jdbc.update(
                "insert into dashboard_stat(stat_key,region_code,payload,computed_at) values('shelter_outcomes','00000','{\"unexpected\":true}'::jsonb,now())");
        mvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.shelterOutcomes").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.lostReports.yesterdayCount").value(0));
    }

    private long animalCase(
            String caseType, LocalDate eventDate, String region, String species, String status) {
        long id =
                jdbc.queryForObject(
                        // CLOSED 는 closed_at 이 있어야 한다 (ck_animal_case_status_dates).
                        "insert into animal_case(case_type,source_type,status,is_matchable,listed_at,species,sex,event_date,closed_at,created_at,updated_at) values(?,'PUBLIC',?,?,now(),?,'UNKNOWN',?,case when ?='CLOSED' then now() end,now(),now()) returning id",
                        Long.class,
                        caseType,
                        status,
                        "SHELTERING".equals(caseType) && "ACTIVE".equals(status),
                        species,
                        eventDate,
                        status);
        jdbc.update(
                "insert into animal_case_location(animal_case_id,location_type,region_code,public_location) values(?,'EVENT',?,'테스트 시군구')",
                id,
                region);
        return id;
    }

    private void sheltering(
            LocalDate eventDate,
            String region,
            String species,
            String status,
            LocalDate noticeEnd) {
        long id = animalCase("SHELTERING", eventDate, region, species, status);
        jdbc.update(
                "insert into shelter_animal(animal_case_id,desertion_no,shelter_id,notice_end_date,neuter_status,last_synced_at) values(?,?,?,?,'UNKNOWN',now())",
                id,
                "insights-" + id,
                shelterId,
                noticeEnd);
    }

    /** 실종 신고 — 기본은 "어제 처음 본" 신고. */
    private long lost(LocalDate eventDate, String region, String species) {
        long id = animalCase("LOST", eventDate, region, species, "ACTIVE");
        jdbc.update(
                "insert into lost_report(animal_case_id,lost_key,first_seen_date,last_seen_date,last_synced_at) values(?,?,?,?,now())",
                id,
                String.format("%064d", id),
                TODAY.minusDays(1),
                TODAY.minusDays(1));
        return id;
    }

    private void lostSeen(long id, LocalDate firstSeen) {
        jdbc.update(
                "update lost_report set first_seen_date=?,last_seen_date=? where animal_case_id=?",
                firstSeen,
                firstSeen,
                id);
    }
}
