package com.meonggo.backend.post.repository;

import com.meonggo.backend.auth.exception.AuthErrorCode;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.dto.CreatePostResponse;
import com.meonggo.backend.post.entity.CaseType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostCreationRepository {
    private final JdbcTemplate jdbc;

    public PostCreationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void requireActive(long memberId, boolean lock) {
        var statuses =
                jdbc.queryForList(
                        "select status from member where id=?" + (lock ? " for update" : ""),
                        String.class,
                        memberId);
        if (statuses.size() != 1 || !"ACTIVE".equals(statuses.getFirst()))
            throw new BusinessException(AuthErrorCode.ACCOUNT_UNAVAILABLE);
    }

    public Optional<ExistingPost> find(long memberId, UUID key) {
        return jdbc
                .query(
                        """
            select p.request_hash, c.id, c.case_type, c.created_at
            from user_post p join animal_case c on c.id=p.animal_case_id
            where p.member_id=? and p.client_request_id=?
            """,
                        (row, number) ->
                                new ExistingPost(
                                        row.getString("request_hash"),
                                        new CreatePostResponse(
                                                row.getLong("id"),
                                                CaseType.valueOf(row.getString("case_type")),
                                                "USER_POST",
                                                "ACTIVE",
                                                0,
                                                row.getTimestamp("created_at").toInstant())),
                        memberId,
                        key)
                .stream()
                .findFirst();
    }

    public record ExistingPost(String hash, CreatePostResponse response) {
        @Override
        public String toString() {
            return "ExistingPost[redacted]";
        }
    }
}
