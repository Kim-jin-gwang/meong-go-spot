package com.meonggo.backend.ingestion.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * D3 홈 인사이트의 읽기 전용 집계 — 지역 조각은 실시간 SQL, 결과 통계는 배치가 적재한 dashboard_stat.
 *
 * <p>지역 범위는 목록(P1)과 같은 규칙이다: 5자리는 시·군·구 정확히 일치, 2자리는 시·도 접두(예 "11" = 서울 전체), null 은 전국(지역 조건 없음).
 * 위치는 EVENT 기준.
 */
@Repository
public class HomeInsightsRepository {
    private final JdbcTemplate jdbc;

    public HomeInsightsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 공고 종료일이 [from, to] 인 보호 중(ACTIVE) 공공 보호동물 수. */
    public int countNoticeClosing(String regionCode, LocalDate from, LocalDate to) {
        Integer count =
                jdbc.queryForObject(
                        """
                        select count(*) from shelter_animal sa
                        join animal_case c on c.id=sa.animal_case_id
                        join animal_case_location l on l.animal_case_id=c.id and l.location_type='EVENT'
                        where c.status='ACTIVE' and sa.notice_end_date between ? and ?
                        """
                                + regionClause(regionCode),
                        Integer.class,
                        withRegion(regionCode, from, to));
        return count == null ? 0 : count;
    }

    /** 공공 보호동물 발견일(event_date)별·축종별 건수. 없는 날은 행이 없다 — 호출자가 0 으로 채운다. */
    public List<SpeciesCount> countShelteringByDay(
            String regionCode, LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                select c.event_date,c.species,count(*) as cnt from animal_case c
                join animal_case_location l on l.animal_case_id=c.id and l.location_type='EVENT'
                where c.source_type='PUBLIC' and c.case_type='SHELTERING'
                  and c.event_date between ? and ?
                """
                        + regionClause(regionCode)
                        + " group by c.event_date,c.species",
                (row, index) ->
                        new SpeciesCount(
                                row.getObject("event_date", LocalDate.class),
                                row.getString("species"),
                                row.getInt("cnt")),
                withRegion(regionCode, from, to));
    }

    /** 스냅샷에 처음 나타난 날이 {@code day} 인 공공 실종 신고 수 (전국). */
    public int countLostFirstSeen(LocalDate day) {
        Integer count =
                jdbc.queryForObject(
                        "select count(*) from lost_report where first_seen_date=?",
                        Integer.class,
                        day);
        return count == null ? 0 : count;
    }

    /** 공공 실종 신고(실종일 기준) 축종별 건수. */
    public List<SpeciesCount> countLostBySpecies(String regionCode, LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                select c.species,count(*) as cnt from animal_case c
                join animal_case_location l on l.animal_case_id=c.id and l.location_type='EVENT'
                where c.source_type='PUBLIC' and c.case_type='LOST'
                  and c.event_date between ? and ?
                """
                        + regionClause(regionCode)
                        + " group by c.species",
                (row, index) -> new SpeciesCount(null, row.getString("species"), row.getInt("cnt")),
                withRegion(regionCode, from, to));
    }

    public Optional<Stat> findStat(String statKey, String regionCode) {
        return jdbc
                .query(
                        "select payload::text as payload,computed_at from dashboard_stat where stat_key=? and region_code=?",
                        (row, index) ->
                                new Stat(
                                        row.getString("payload"),
                                        row.getTimestamp("computed_at").toInstant()),
                        statKey,
                        regionCode)
                .stream()
                .findFirst();
    }

    /** 5자리 = 일치, 2자리 = 접두(LIKE 'XX%'), null = 조건 없음. 코드 형식은 서비스가 이미 검증했다. */
    static String regionClause(String regionCode) {
        if (regionCode == null) return "";
        return regionCode.length() == 2 ? " and l.region_code like ?" : " and l.region_code=?";
    }

    static Object[] withRegion(String regionCode, Object... leading) {
        List<Object> params = new ArrayList<>(List.of(leading));
        if (regionCode != null)
            params.add(regionCode.length() == 2 ? regionCode + "%" : regionCode);
        return params.toArray();
    }

    public record SpeciesCount(LocalDate date, String species, int count) {}

    public record Stat(String payload, Instant computedAt) {}
}
