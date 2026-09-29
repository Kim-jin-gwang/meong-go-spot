package com.meonggo.backend.adoption.repository;

import com.meonggo.backend.adoption.query.AdoptionCursorCodec.Position;
import com.meonggo.backend.adoption.query.AdoptionListQuery;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 입양 카드 조회는 보호소 연락처·주소, 위치 암호문·좌표, 원천 상태 문자열을 SELECT하지 않는다. */
@Repository
public class AdoptionListRepository {
    private static final String SELECT =
            """
        select c.id,c.species,c.breed_name,c.sex,c.color,l.public_location,
               photo.storage_uri,sa.notice_end_date,sa.last_synced_at,
               (favorite.member_id is not null) as favorited
        from animal_case c
        join shelter_animal sa on sa.animal_case_id=c.id
        join animal_case_location l
          on l.animal_case_id=c.id and l.location_type='EVENT'
        join lateral (
          select p.storage_uri
          from animal_photo p
          where p.animal_case_id=c.id
            and p.sort_order=0
            and p.storage_type='PUBLIC_URL'
        ) photo on true
        left join adoption_favorite favorite
          on favorite.animal_case_id=c.id and favorite.member_id=:memberId
        where c.source_type='PUBLIC'
          and c.case_type='SHELTERING'
          and c.status='ACTIVE'
          and c.deleted_at is null
          and sa.process_state_raw='보호중'
          and sa.notice_end_date is not null
          and sa.notice_end_date<:asOfDate
          and not exists(
            select 1 from adoption_swipe swipe
            where swipe.animal_case_id=c.id and swipe.member_id=:memberId)
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public AdoptionListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(
            AdoptionListQuery query,
            LocalDate asOfDate,
            Long memberId,
            Position position,
            int limit) {
        var sql = new StringBuilder(SELECT);
        var parameters =
                new MapSqlParameterSource()
                        .addValue("memberId", memberId)
                        .addValue("asOfDate", asOfDate)
                        .addValue("limit", limit);
        region(sql, parameters, query.regionCode());
        if (query.species() != null) {
            sql.append(" and c.species=:species");
            parameters.addValue("species", query.species().name());
        } else {
            sql.append(" and c.species in ('DOG','CAT')");
        }
        // 성별을 고르면 원천 성별이 UNKNOWN 인 건은 뺀다 — 고른 성별이 맞는지 확인할 수 없다 (AD1).
        if (query.sex() != null) {
            sql.append(" and c.sex=:sex");
            parameters.addValue("sex", query.sex().name());
        }
        if (position != null) {
            sql.append(" and (sa.notice_end_date,c.id)>(:noticeEndDate,:postId)");
            parameters
                    .addValue("noticeEndDate", position.noticeEndDate())
                    .addValue("postId", position.postId());
        }
        // 수집기도 먼저 잠그는 상태 행을 기다린 뒤 commit된 최신 자격으로 다시 판정한다.
        sql.append(" order by sa.notice_end_date asc,c.id asc limit :limit for share of sa");
        return jdbc.query(sql.toString(), parameters, (row, index) -> map(row));
    }

    /** 5자리는 시·군·구 일치, 2자리는 시·도 접두(LIKE 'XX%'), null 은 전국. 형식은 AdoptionListInputPolicy 가 검증했다. */
    private void region(StringBuilder sql, MapSqlParameterSource parameters, String regionCode) {
        if (regionCode == null) return;
        if (regionCode.length() == 2) {
            sql.append(" and l.region_code like :regionCode");
            parameters.addValue("regionCode", regionCode + "%");
        } else {
            sql.append(" and l.region_code=:regionCode");
            parameters.addValue("regionCode", regionCode);
        }
    }

    private Row map(ResultSet row) throws SQLException {
        return new Row(
                row.getLong("id"),
                Species.valueOf(row.getString("species")),
                row.getString("breed_name"),
                Sex.valueOf(row.getString("sex")),
                row.getString("color"),
                row.getString("public_location"),
                row.getString("storage_uri"),
                row.getObject("notice_end_date", LocalDate.class),
                row.getTimestamp("last_synced_at").toInstant(),
                row.getBoolean("favorited"));
    }

    public record Row(
            long postId,
            Species species,
            String breedName,
            Sex sex,
            String color,
            String publicLocation,
            String photoUrl,
            LocalDate noticeEndDate,
            Instant lastSyncedAt,
            boolean favorited) {}
}
