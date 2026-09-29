package com.meonggo.backend.ingestion.service;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.ingestion.dto.InsightsResponse;
import com.meonggo.backend.ingestion.repository.HomeInsightsRepository;
import com.meonggo.backend.ingestion.repository.HomeInsightsRepository.SpeciesCount;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * D3 홈 인사이트.
 *
 * <p>"오늘"은 D2 와 같은 dataSourceClock 의 Asia/Seoul 날짜다. 지역 조각(공고 마감·주간 입소·지역 실종)은 regionCode 범위로 계산한다
 * — 5자리 시·군·구, 2자리 시·도 전체, 없으면 전국(2026-09-17 부터; 그 전엔 지역이 없으면 null 이었다). 결과 통계는
 * 배치(`data/collector/shelter_outcomes.py`)가 dashboard_stat 에 넣은 값을 읽는다 — 아직 없거나 payload 가 깨져 있으면 그
 * 조각만 null 이다.
 */
@Service
public class HomeInsightsService {
    private static final Logger LOG = LoggerFactory.getLogger(HomeInsightsService.class);
    public static final String OUTCOMES_STAT_KEY = "shelter_outcomes";
    public static final String NATIONAL_REGION = "00000";
    static final int NOTICE_CLOSING_DAYS = 3;
    static final int WEEKLY_DAYS = 7;
    static final int LOST_WINDOW_DAYS = 30;
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final HomeInsightsRepository insights;
    private final IngestionStatusService ingestion;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate reads;

    public HomeInsightsService(
            HomeInsightsRepository insights,
            IngestionStatusService ingestion,
            ObjectMapper mapper,
            @Qualifier("dataSourceClock") Clock clock,
            PlatformTransactionManager transactions) {
        this.insights = insights;
        this.ingestion = ingestion;
        this.mapper = mapper;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(30);
    }

    public InsightsResponse.Insights insights(String regionCode) {
        // 목록(P1)과 같은 범위 규칙: 5자리 시·군·구, 2자리 시·도 전체, 없으면 전국.
        if (regionCode != null && !regionCode.matches("[0-9]{2}|[0-9]{5}"))
            throw new InputValidationException("regionCode", "2자리 시·도 또는 5자리 시·군·구 코드를 입력해 주세요.");
        LocalDate today = clock.instant().atZone(SERVICE_ZONE).toLocalDate();
        return reads.execute(
                transaction ->
                        new InsightsResponse.Insights(
                                ingestion.dailySummary().summary(),
                                noticeClosing(regionCode, today),
                                weeklyIntake(regionCode, today),
                                shelterOutcomes(),
                                lostReports(regionCode, today)));
    }

    private InsightsResponse.NoticeClosing noticeClosing(String regionCode, LocalDate today) {
        int count =
                insights.countNoticeClosing(regionCode, today, today.plusDays(NOTICE_CLOSING_DAYS));
        return new InsightsResponse.NoticeClosing(regionCode, NOTICE_CLOSING_DAYS, count);
    }

    private InsightsResponse.WeeklyIntake weeklyIntake(String regionCode, LocalDate today) {
        LocalDate from = today.minusDays(WEEKLY_DAYS - 1);
        List<SpeciesCount> rows = insights.countShelteringByDay(regionCode, from, today);
        List<InsightsResponse.DailyCount> days = new ArrayList<>(WEEKLY_DAYS);
        int total = 0;
        for (int offset = 0; offset < WEEKLY_DAYS; offset++) {
            LocalDate day = from.plusDays(offset);
            int dogs = 0;
            int cats = 0;
            for (SpeciesCount row : rows) {
                if (!day.equals(row.date())) continue;
                if ("DOG".equals(row.species())) dogs += row.count();
                else if ("CAT".equals(row.species())) cats += row.count();
            }
            total += dogs + cats;
            days.add(new InsightsResponse.DailyCount(day, dogs, cats));
        }
        return new InsightsResponse.WeeklyIntake(regionCode, from, today, total, days);
    }

    private InsightsResponse.ShelterOutcomes shelterOutcomes() {
        var stat = insights.findStat(OUTCOMES_STAT_KEY, NATIONAL_REGION).orElse(null);
        if (stat == null) return null;
        try {
            JsonNode payload = mapper.readTree(stat.payload());
            return new InsightsResponse.ShelterOutcomes(
                    LocalDate.parse(payload.get("windowStart").asString()),
                    LocalDate.parse(payload.get("windowEnd").asString()),
                    payload.get("closedCount").asLong(),
                    payload.get("returnRate").asDouble(),
                    payload.get("adoptionRate").asDouble(),
                    payload.hasNonNull("averageNoticeDays")
                            ? payload.get("averageNoticeDays").asDouble()
                            : null,
                    stat.computedAt());
        } catch (RuntimeException ex) {
            // 배치가 적은 형식이 바뀌었으면 홈 전체를 깨지 말고 이 카드만 비운다.
            LOG.warn(
                    "dashboard_stat {} payload unreadable: {}",
                    OUTCOMES_STAT_KEY,
                    ex.getClass().getSimpleName());
            return null;
        }
    }

    private InsightsResponse.LostReports lostReports(String regionCode, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        int yesterdayCount = insights.countLostFirstSeen(yesterday);
        int dogs = 0;
        int cats = 0;
        int total = 0;
        for (SpeciesCount row :
                insights.countLostBySpecies(
                        regionCode, today.minusDays(LOST_WINDOW_DAYS - 1), today)) {
            total += row.count();
            if ("DOG".equals(row.species())) dogs += row.count();
            else if ("CAT".equals(row.species())) cats += row.count();
        }
        return new InsightsResponse.LostReports(
                yesterday, yesterdayCount, regionCode, total, dogs, cats);
    }
}
