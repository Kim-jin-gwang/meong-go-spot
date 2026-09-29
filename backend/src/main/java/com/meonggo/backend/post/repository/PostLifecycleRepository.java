package com.meonggo.backend.post.repository;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.photo.entity.AnimalPhoto;
import com.meonggo.backend.post.dto.PostClosureResponse;
import com.meonggo.backend.post.dto.PostPhotoReplacementResponse;
import com.meonggo.backend.post.dto.PostPhotoReplacementResponse.Photo;
import com.meonggo.backend.post.entity.CloseReason;
import com.meonggo.backend.post.exception.PostErrorCode;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostLifecycleRepository {
    private final JdbcTemplate jdbc;
    private final EntityManager entities;

    public PostLifecycleRepository(JdbcTemplate jdbc, EntityManager entities) {
        this.jdbc = jdbc;
        this.entities = entities;
    }

    public PostClosureResponse close(long postId, long version, CloseReason reason) {
        requireIncrementable(version);
        var times =
                jdbc.query(
                        """
            update animal_case set status='CLOSED',is_matchable=false,closed_at=current_timestamp,
              updated_at=current_timestamp,version=version+1
            where id=? and version=? and status='ACTIVE' and source_type='USER'
            returning closed_at
            """,
                        (row, index) -> row.getTimestamp("closed_at").toInstant(),
                        postId,
                        version);
        if (times.size() != 1) throw conflict();
        if (jdbc.update(
                        "update user_post set close_reason=? where animal_case_id=?",
                        reason.name(),
                        postId)
                != 1) throw conflict();
        return new PostClosureResponse(postId, "CLOSED", version + 1, reason, times.getFirst());
    }

    public PostPhotoReplacementResponse replace(
            long postId, long version, List<AnimalPhoto> photos) {
        requireIncrementable(version);
        List<Instant> times =
                jdbc.query(
                        """
            update animal_case set version=version+1,updated_at=current_timestamp
            where id=? and version=? and status='ACTIVE' and source_type='USER' returning updated_at
            """,
                        (row, index) -> row.getTimestamp("updated_at").toInstant(),
                        postId,
                        version);
        if (times.size() != 1) throw conflict();
        jdbc.update("delete from animal_photo where animal_case_id=?", postId);
        photos.forEach(entities::persist);
        entities.flush();
        return new PostPhotoReplacementResponse(
                postId,
                version + 1,
                photos.stream()
                        .map(
                                photo ->
                                        new Photo(
                                                photo.getId(),
                                                "/api/v1/photos/" + photo.getId(),
                                                photo.getSortOrder()))
                        .toList(),
                times.getFirst());
    }

    private void requireIncrementable(long version) {
        if (version == Long.MAX_VALUE) throw conflict();
    }

    private BusinessException conflict() {
        return new BusinessException(PostErrorCode.VERSION_CONFLICT);
    }
}
