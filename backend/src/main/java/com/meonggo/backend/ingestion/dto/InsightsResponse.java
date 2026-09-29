package com.meonggo.backend.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * D3 홈 인사이트 — 홈의 가로 스크롤 카드 5장을 한 번에 채운다 (docs/api-spec.md D3).
 *
 * <p>조각마다 독립이다. 지역이 없으면 지역 카드는 null, 집계 배치가 아직 안 돌았으면 shelterOutcomes 가 null 이며, 앱은 null 조각을 "준비 중"
 * 카드로 그린다. 그래서 null 을 생략하지 않고 그대로 내려보낸다.
 */
public final class InsightsResponse {
    private InsightsResponse() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Insights(
            IngestionResponse.Summary dailyIntake,
            NoticeClosing noticeClosing,
            WeeklyIntake weeklyIntake,
            ShelterOutcomes shelterOutcomes,
            LostReports lostReports) {}

    /** 지역 범위(5자리 시·군·구, 2자리 시·도, null 전국)에서 공고 종료가 {@code withinDays} 일 안인 보호 중 동물 수. */
    public record NoticeClosing(String regionCode, int withinDays, int animalCount) {}

    /** 지역 범위의 최근 7일 일별 입소(발견일 기준) — 개·고양이. regionCode 는 요청값 그대로(전국이면 null). */
    public record WeeklyIntake(
            String regionCode, LocalDate from, LocalDate to, int total, List<DailyCount> days) {}

    public record DailyCount(LocalDate date, int dogCount, int catCount) {}

    /** 전국 보호소 결과 통계 — DATA 배치가 dashboard_stat 에 적재한 값을 그대로 전달한다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ShelterOutcomes(
            LocalDate windowStart,
            LocalDate windowEnd,
            long closedCount,
            double returnRate,
            double adoptionRate,
            Double averageNoticeDays,
            Instant computedAt) {}

    /** 공공 실종 신고 — 어제 전국 신규 신고 수와 지역 범위의 최근 30일 신고 수(전국이면 regionCode 만 null). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LostReports(
            LocalDate yesterday,
            int yesterdayCount,
            String regionCode,
            Integer regionLast30DaysCount,
            Integer regionDogCount,
            Integer regionCatCount) {}
}
