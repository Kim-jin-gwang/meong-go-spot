package com.meonggo.backend.adoption.repository;

import com.meonggo.backend.adoption.query.AdoptionFavoriteCursorCodec.Position;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 찜 카드 조회도 연락처·주소, 정확한 위치·좌표와 원천 상태 문자열을 SELECT하지 않는다. */
@Repository
public class AdoptionFavoriteRepository {
    private static final String LIST =
            """
        select favorite.animal_case_id,favorite.created_at,c.species,c.breed_name,c.sex,c.color,
               event.public_location,photo.storage_uri,sa.notice_end_date,sa.last_synced_at,
               (c.source_type='PUBLIC'
                 and c.case_type='SHELTERING'
                 and c.status='ACTIVE'
                 and c.deleted_at is null
                 and c.species in ('DOG','CAT')
                 and sa.process_state_raw='보호중'
                 and sa.notice_end_date is not null
                 and sa.notice_end_date<:asOfDate
                 and event.animal_case_id is not null
                 and photo.storage_uri is not null) as database_eligible
        from adoption_favorite favorite
        join animal_case c on c.id=favorite.animal_case_id
        left join shelter_animal sa on sa.animal_case_id=c.id
        left join animal_case_location event
          on event.animal_case_id=c.id and event.location_type='EVENT'
        left join lateral (
          select p.storage_uri
          from animal_photo p
          where p.animal_case_id=c.id
            and p.sort_order=0
            and p.storage_type='PUBLIC_URL'
        ) photo on true
        where favorite.member_id=:memberId
        """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public AdoptionFavoriteRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    public List<Row> find(long memberId, LocalDate asOfDate, Position position, int limit) {
        var sql = new StringBuilder(LIST);
        var parameters =
                new MapSqlParameterSource()
                        .addValue("memberId", memberId)
                        .addValue("asOfDate", asOfDate)
                        .addValue("limit", limit);
        if (position != null) {
            sql.append(" and (favorite.created_at,favorite.animal_case_id)<(:createdAt,:postId)");
            parameters
                    .addValue("createdAt", Timestamp.from(position.favoritedAt()))
                    .addValue("postId", position.postId());
        }
        sql.append(" order by favorite.created_at desc,favorite.animal_case_id desc limit :limit");
        return namedJdbc.query(sql.toString(), parameters, (row, index) -> map(row));
    }

    public boolean exists(long memberId, long postId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select exists(select 1 from adoption_favorite where member_id=? and animal_case_id=?)",
                        Boolean.class,
                        memberId,
                        postId));
    }

    public Optional<String> findEligiblePhoto(long postId, LocalDate asOfDate) {
        return jdbc
                .query(
                        """
            select photo.storage_uri
            from animal_case c
            join shelter_animal sa on sa.animal_case_id=c.id
            join animal_case_location event
              on event.animal_case_id=c.id and event.location_type='EVENT'
            join animal_photo photo
              on photo.animal_case_id=c.id
             and photo.sort_order=0
             and photo.storage_type='PUBLIC_URL'
            where c.id=?
              and c.source_type='PUBLIC'
              and c.case_type='SHELTERING'
              and c.status='ACTIVE'
              and c.deleted_at is null
              and c.species in ('DOG','CAT')
              and sa.process_state_raw='보호중'
              and sa.notice_end_date is not null
              and sa.notice_end_date<?
            for share of c,sa,event,photo
            """,
                        (row, index) -> row.getString("storage_uri"),
                        postId,
                        asOfDate)
                .stream()
                .findFirst();
    }

    public void insert(long memberId, long postId) {
        jdbc.update(
                """
            insert into adoption_favorite(member_id,animal_case_id,created_at)
            values(?,?,clock_timestamp())
            on conflict (member_id,animal_case_id) do nothing
            """,
                memberId,
                postId);
    }

    public void delete(long memberId, long postId) {
        jdbc.update(
                "delete from adoption_favorite where member_id=? and animal_case_id=?",
                memberId,
                postId);
    }

    private Row map(ResultSet row) throws SQLException {
        Timestamp lastSyncedAt = row.getTimestamp("last_synced_at");
        return new Row(
                row.getLong("animal_case_id"),
                Species.valueOf(row.getString("species")),
                row.getString("breed_name"),
                Sex.valueOf(row.getString("sex")),
                row.getString("color"),
                row.getString("public_location"),
                row.getString("storage_uri"),
                row.getObject("notice_end_date", LocalDate.class),
                lastSyncedAt == null ? null : lastSyncedAt.toInstant(),
                row.getTimestamp("created_at").toInstant(),
                row.getBoolean("database_eligible"));
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
            Instant favoritedAt,
            boolean databaseEligible) {}
}
