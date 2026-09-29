package com.meonggo.backend.adoption.repository;

import com.meonggo.backend.adoption.query.AdoptionSwipeCursorCodec.Position;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 넘김 목록도 연락처·주소, 정확한 위치·좌표와 원천 상태 문자열을 SELECT하지 않는다. */
@Repository
public class AdoptionSwipeRepository {
    private static final String LIST =
            """
        select swipe.animal_case_id,swipe.swiped_at,c.species,c.breed_name,c.sex,c.color,
               event.public_location,photo.storage_uri,sa.notice_end_date,sa.last_synced_at,
               (favorite.member_id is not null) as favorited,
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
        from adoption_swipe swipe
        join animal_case c on c.id=swipe.animal_case_id
        left join shelter_animal sa on sa.animal_case_id=c.id
        left join animal_case_location event
          on event.animal_case_id=c.id and event.location_type='EVENT'
        left join adoption_favorite favorite
          on favorite.animal_case_id=c.id and favorite.member_id=swipe.member_id
        left join lateral (
          select p.storage_uri
          from animal_photo p
          where p.animal_case_id=c.id
            and p.sort_order=0
            and p.storage_type='PUBLIC_URL'
        ) photo on true
        where swipe.member_id=:memberId
        """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public AdoptionSwipeRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
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
            sql.append(" and (swipe.swiped_at,swipe.animal_case_id)<(:swipedAt,:postId)");
            parameters
                    .addValue("swipedAt", Timestamp.from(position.swipedAt()))
                    .addValue("postId", position.postId());
        }
        sql.append(" order by swipe.swiped_at desc,swipe.animal_case_id desc limit :limit");
        return namedJdbc.query(sql.toString(), parameters, (row, index) -> map(row));
    }

    /**
     * 공공 보호 게시 건이기만 하면 참이다.
     *
     * <p>찜(AD3)과 달리 공고 종료·원천 상태·대표 사진을 보지 않는다. 카드를 보고 넘기는 사이에 원천 상태가 바뀔 수 있는데, 그때 기록이 거절되면 그 동물이 다음
     * 조회에 다시 올라온다 (api-spec AD5).
     */
    public boolean isPublicShelteringCase(long postId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
            select exists(
              select 1 from animal_case
              where id=? and source_type='PUBLIC' and case_type='SHELTERING' and deleted_at is null)
            """,
                        Boolean.class,
                        postId));
    }

    /** 같은 동물을 다시 보내도 최초 넘긴 시각을 바꾸지 않는다 (api-spec AD5). */
    public void insert(long memberId, long postId, Instant swipedAt) {
        jdbc.update(
                """
            insert into adoption_swipe(member_id,animal_case_id,swiped_at)
            values(?,?,?)
            on conflict (member_id,animal_case_id) do nothing
            """,
                memberId,
                postId,
                Timestamp.from(swipedAt));
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
                row.getTimestamp("swiped_at").toInstant(),
                row.getBoolean("favorited"),
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
            Instant swipedAt,
            boolean favorited,
            boolean databaseEligible) {}
}
