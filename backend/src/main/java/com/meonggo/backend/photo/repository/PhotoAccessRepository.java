package com.meonggo.backend.photo.repository;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PhotoAccessRepository {
    private final JdbcTemplate jdbc;

    public PhotoAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReadablePhoto> findReadable(long photoId, Long viewerId) {
        return jdbc
                .query(
                        """
                select p.storage_uri, p.byte_size, p.checksum_sha256
                from animal_photo p
                join animal_case c on c.id=p.animal_case_id
                join user_post u on u.animal_case_id=c.id
                join member m on m.id=u.member_id
                where p.id=? and p.storage_type='USER_UPLOAD' and c.source_type='USER'
                  and m.status='ACTIVE' and c.deleted_at is null
                  and (c.status='ACTIVE' or (c.status='CLOSED' and u.member_id=?
                    and c.closed_at + interval '90 days' > CURRENT_TIMESTAMP))
                """,
                        (rs, row) ->
                                new ReadablePhoto(rs.getString(1), rs.getLong(2), rs.getString(3)),
                        photoId,
                        viewerId)
                .stream()
                .findFirst();
    }

    public record ReadablePhoto(String path, long byteSize, String checksum) {
        @Override
        public String toString() {
            return "ReadablePhoto[redacted]";
        }
    }
}
