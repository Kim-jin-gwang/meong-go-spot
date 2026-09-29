package com.meonggo.backend.post.repository;

import com.meonggo.backend.post.entity.CaseType;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostMutationRepository {
    private final JdbcTemplate jdbc;

    public PostMutationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Target> find(long postId, boolean lock) {
        return jdbc
                .query(
                        """
            select c.id,c.case_type,c.source_type,c.status,c.version,u.member_id
            from animal_case c
            left join user_post u on u.animal_case_id=c.id and c.source_type='USER'
            left join member m on m.id=u.member_id
            where c.id=? and c.deleted_at is null and c.status<>'DELETED'
              and (c.source_type='PUBLIC' or (m.status='ACTIVE' and m.deleted_at is null))
            """
                                + (lock ? " for update of c" : ""),
                        (row, index) ->
                                new Target(
                                        row.getLong("id"),
                                        CaseType.valueOf(row.getString("case_type")),
                                        row.getString("source_type"),
                                        row.getString("status"),
                                        row.getLong("version"),
                                        row.getObject("member_id", Long.class)),
                        postId)
                .stream()
                .findFirst();
    }

    public record Target(
            long postId, CaseType type, String source, String status, long version, Long ownerId) {}
}
