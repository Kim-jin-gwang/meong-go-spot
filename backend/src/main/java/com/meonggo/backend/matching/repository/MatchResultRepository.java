package com.meonggo.backend.matching.repository;

import com.meonggo.backend.global.common.photo.PublicPhotoUrl;
import com.meonggo.backend.matching.dto.MatchCandidatesResponse.Author;
import com.meonggo.backend.matching.dto.MatchCandidatesResponse.Candidate;
import com.meonggo.backend.matching.dto.MatchCandidatesResponse.LatestRun;
import com.meonggo.backend.matching.dto.MatchCandidatesResponse.PostSummary;
import com.meonggo.backend.matching.dto.MatchCandidatesResponse.Shelter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchResultRepository {
    private static final String RUN_FIELDS =
            """
            select id,query_case_version,status,candidate_count,started_at,completed_at,created_at,
              case when status='FAILED' then
                case when error_code='MATCH_TIMEOUT' then 'MATCH_TIMEOUT' else 'MATCH_FAILED' end
              end as safe_error
            from match_run where query_case_id=?
            """;
    private final JdbcTemplate jdbc;

    public MatchResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Run> latest(long postId) {
        return jdbc
                .query(RUN_FIELDS + " order by created_at desc,id desc limit 1", this::run, postId)
                .stream()
                .findFirst();
    }

    public Optional<Run> latestSuccess(long postId) {
        return jdbc
                .query(
                        RUN_FIELDS
                                + " and status='SUCCEEDED' order by completed_at desc,id desc limit 1",
                        this::run,
                        postId)
                .stream()
                .findFirst();
    }

    public List<Candidate> candidates(long runId) {
        return jdbc.query(
                """
                select mc.rank,c.id,c.source_type,c.status,c.name,c.species,c.breed_name,c.sex,c.color,
                  c.event_date,l.public_location,m.nickname,s.name as shelter_name,s.phone as shelter_phone,
                  p.id as photo_id,p.storage_uri
                from match_candidate mc
                join animal_case c on c.id=mc.target_case_id
                join animal_case_location l on l.animal_case_id=c.id and l.location_type='EVENT'
                left join user_post u on u.animal_case_id=c.id and c.source_type='USER'
                left join member m on m.id=u.member_id
                left join shelter_animal sa on sa.animal_case_id=c.id and c.source_type='PUBLIC'
                left join shelter s on s.id=sa.shelter_id
                left join lateral (
                  select ap.id,case when c.source_type='PUBLIC' then ap.storage_uri end as storage_uri
                  from animal_photo ap where ap.animal_case_id=c.id
                    and ap.storage_type=case when c.source_type='USER' then 'USER_UPLOAD' else 'PUBLIC_URL' end
                  order by ap.sort_order,ap.id limit 1
                ) p on true
                where mc.match_run_id=? and c.case_type='SHELTERING' and c.is_matchable=true
                  and c.deleted_at is null
                  and ((c.source_type='USER' and c.status='ACTIVE' and m.status='ACTIVE' and m.deleted_at is null)
                    or (c.source_type='PUBLIC' and c.status in ('ACTIVE','CLOSED') and s.id is not null))
                order by mc.rank limit 20
                """,
                this::candidate,
                runId);
    }

    private Run run(ResultSet row, int index) throws SQLException {
        String status = row.getString("status");
        return new Run(
                row.getLong("id"),
                row.getLong("query_case_version"),
                new LatestRun(
                        row.getLong("id"),
                        status,
                        "SUCCEEDED".equals(status) ? row.getInt("candidate_count") : null,
                        row.getString("safe_error"),
                        instant(row, "started_at"),
                        instant(row, "completed_at"),
                        instant(row, "created_at")));
    }

    private Candidate candidate(ResultSet row, int index) throws SQLException {
        boolean user = "USER".equals(row.getString("source_type"));
        Long photoId = row.getObject("photo_id", Long.class);
        String thumbnail =
                user
                        ? photoId == null ? null : "/api/v1/photos/" + photoId
                        : PublicPhotoUrl.sanitize(row.getString("storage_uri"));
        return new Candidate(
                row.getInt("rank"),
                new PostSummary(
                        row.getLong("id"),
                        "SHELTERING",
                        user ? "USER_POST" : "SHELTER",
                        row.getString("status"),
                        row.getString("name"),
                        row.getString("species"),
                        row.getString("breed_name"),
                        row.getString("sex"),
                        row.getString("color"),
                        row.getObject("event_date", LocalDate.class),
                        row.getString("public_location"),
                        thumbnail,
                        user ? new Author(row.getString("nickname")) : null,
                        user
                                ? null
                                : new Shelter(
                                        row.getString("shelter_name"),
                                        row.getString("shelter_phone"))));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Run(long id, long queryCaseVersion, LatestRun summary) {}
}
