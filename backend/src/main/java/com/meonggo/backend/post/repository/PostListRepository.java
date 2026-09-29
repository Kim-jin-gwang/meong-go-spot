package com.meonggo.backend.post.repository;

import com.meonggo.backend.post.entity.CaseStatus;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import com.meonggo.backend.post.query.PostCursorCodec.Position;
import com.meonggo.backend.post.query.PostListQuery;
import com.meonggo.backend.post.query.PostListSort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 공개 목록은 위치 암호문·좌표·연락처·작성자 닉네임을 SELECT하지 않는다. */
@Repository
public class PostListRepository {
    private static final String SELECT =
            """
        select c.id,c.case_type,c.source_type,c.status,c.version,c.name,c.species,c.breed_name,
          c.sex,c.color,c.event_date,c.listed_at,c.updated_at,l.public_location,
          photo.id as photo_id,photo.storage_type,photo.storage_uri
        from animal_case c
        join animal_case_location l on l.animal_case_id=c.id and l.location_type='EVENT'
        left join user_post u on u.animal_case_id=c.id and c.source_type='USER'
        left join member m on m.id=u.member_id
        left join shelter_animal sa on sa.animal_case_id=c.id and c.source_type='PUBLIC'
        left join lost_report lr on lr.animal_case_id=c.id and c.source_type='PUBLIC'
        left join lateral (
          select p.id,p.storage_type,p.storage_uri from animal_photo p
          where p.animal_case_id=c.id and p.sort_order=0
        ) photo on true
        where c.deleted_at is null
          and ((c.source_type='USER' and m.status='ACTIVE')
            or (c.source_type='PUBLIC' and (sa.animal_case_id is not null or lr.animal_case_id is not null)))
        """;
    private final NamedParameterJdbcTemplate jdbc;

    public PostListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(PostListQuery query, Long ownerId, Position position) {
        var sql = new StringBuilder(SELECT);
        var params = new MapSqlParameterSource();
        if (ownerId == null) {
            sql.append(" and c.status='ACTIVE'");
        } else {
            sql.append(" and c.source_type='USER' and u.member_id=:owner");
            sql.append(
                    " and (c.status='ACTIVE' or (c.status='CLOSED'"
                            + " and c.closed_at + interval '90 days' > CURRENT_TIMESTAMP))");
            params.addValue("owner", ownerId);
        }
        equal(sql, params, "c.case_type", "type", query.type());
        equal(sql, params, "c.species", "species", query.species());
        equal(sql, params, "c.sex", "sex", query.sex());
        equal(sql, params, "c.source_type", "source", query.source());
        equal(sql, params, "c.status", "status", query.status());
        region(sql, params, query.regionCode());
        substring(sql, params, "c.breed_name", "breed", query.breedName());
        substring(sql, params, "c.color", "color", query.color());
        if (position != null) {
            params.addValue("listed", Timestamp.from(position.listedAt()))
                    .addValue("postId", position.postId());
        }
        if (query.sort() == PostListSort.LATEST) {
            if (position != null) sql.append(" and (c.listed_at,c.id)<(:listed,:postId)");
            sql.append(" order by c.listed_at desc,c.id desc limit 11");
        } else {
            if (position != null) sql.append(" and (c.listed_at,c.id)>(:listed,:postId)");
            sql.append(" order by c.listed_at asc,c.id asc limit 11");
        }
        return jdbc.query(sql.toString(), params, (row, index) -> map(row));
    }

    /** 5자리는 시·군·구 일치, 2자리는 시·도 접두(LIKE 'XX%'), null 은 전국. 형식은 PostListInputPolicy 가 검증했다. */
    private void region(StringBuilder sql, MapSqlParameterSource params, String regionCode) {
        if (regionCode == null) return;
        if (regionCode.length() == 2) {
            sql.append(" and l.region_code like :region");
            params.addValue("region", regionCode + "%");
        } else {
            sql.append(" and l.region_code=:region");
            params.addValue("region", regionCode);
        }
    }

    private void equal(
            StringBuilder sql,
            MapSqlParameterSource params,
            String column,
            String name,
            Object value) {
        if (value == null) return;
        sql.append(" and ").append(column).append("=:").append(name);
        params.addValue(name, value instanceof Enum<?> e ? e.name() : value);
    }

    private void substring(
            StringBuilder sql,
            MapSqlParameterSource params,
            String column,
            String name,
            String value) {
        if (value == null) return;
        // strpos는 %, _, 역슬래시도 문자 그대로 비교하므로 LIKE escape 실수를 피한다.
        sql.append(" and strpos(lower(")
                .append(column)
                .append("),lower(:")
                .append(name)
                .append("))>0");
        params.addValue(name, value);
    }

    /** 출처 배지 — 공공 SHELTERING 은 SHELTER, 공공 LOST(분실 신고)는 PUBLIC_LOST (docs/api-spec.md §3.5). */
    static String sourceLabel(String sourceType, String caseType) {
        if ("USER".equals(sourceType)) return "USER_POST";
        return "LOST".equals(caseType) ? "PUBLIC_LOST" : "SHELTER";
    }

    private Row map(ResultSet row) throws SQLException {
        return new Row(
                row.getLong("id"),
                CaseType.valueOf(row.getString("case_type")),
                sourceLabel(row.getString("source_type"), row.getString("case_type")),
                CaseStatus.valueOf(row.getString("status")),
                row.getLong("version"),
                row.getString("name"),
                Species.valueOf(row.getString("species")),
                row.getString("breed_name"),
                Sex.valueOf(row.getString("sex")),
                row.getString("color"),
                row.getObject("event_date", LocalDate.class),
                row.getTimestamp("listed_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getString("public_location"),
                row.getObject("photo_id", Long.class),
                row.getString("storage_type"),
                row.getString("storage_uri"));
    }

    public record Row(
            long postId,
            CaseType type,
            String source,
            CaseStatus status,
            long version,
            String name,
            Species species,
            String breedName,
            Sex sex,
            String color,
            LocalDate eventDate,
            Instant listedAt,
            Instant updatedAt,
            String publicLocation,
            Long photoId,
            String storageType,
            String storageUri) {
        @Override
        public String toString() {
            return "PostListRow[redacted]";
        }
    }
}
