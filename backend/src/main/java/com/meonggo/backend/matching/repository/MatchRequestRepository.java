package com.meonggo.backend.matching.repository;

import com.meonggo.backend.matching.dto.MatchRequestResponse;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MatchRequestRepository {
    private final JdbcTemplate jdbc;

    public MatchRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MatchRequestResponse> findActive(long postId) {
        return jdbc
                .query(
                        """
            select id,status,created_at
            from match_run
            where query_case_id=? and status in ('PENDING','RUNNING')
            order by created_at desc,id desc
            limit 1
            """,
                        (row, index) ->
                                new MatchRequestResponse(
                                        postId,
                                        row.getLong("id"),
                                        row.getString("status"),
                                        row.getTimestamp("created_at").toInstant()),
                        postId)
                .stream()
                .findFirst();
    }

    public MatchRequestResponse insert(
            long postId, long version, String modelId, String modelVersion) {
        return jdbc.queryForObject(
                """
            insert into match_run(
                query_case_id,query_case_version,status,model_id,model_version,created_at)
            values(?,?,'PENDING',?,?,clock_timestamp())
            returning id,status,created_at
            """,
                (row, index) ->
                        new MatchRequestResponse(
                                postId,
                                row.getLong("id"),
                                row.getString("status"),
                                row.getTimestamp("created_at").toInstant()),
                postId,
                version,
                modelId,
                modelVersion);
    }
}
