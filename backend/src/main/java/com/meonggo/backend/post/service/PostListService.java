package com.meonggo.backend.post.service;

import com.meonggo.backend.global.common.photo.PublicPhotoUrl;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.post.dto.PostListResponse;
import com.meonggo.backend.post.dto.PostListResponse.OwnSummary;
import com.meonggo.backend.post.dto.PostListResponse.Page;
import com.meonggo.backend.post.dto.PostListResponse.PublicSummary;
import com.meonggo.backend.post.query.PostCursorCodec;
import com.meonggo.backend.post.query.PostCursorCodec.Position;
import com.meonggo.backend.post.query.PostListQuery;
import com.meonggo.backend.post.repository.PostListRepository;
import com.meonggo.backend.post.repository.PostListRepository.Row;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class PostListService {
    private final PostListRepository posts;
    private final PostCursorCodec cursors;

    public PostListService(PostListRepository posts, PostCursorCodec cursors) {
        this.posts = posts;
        this.cursors = cursors;
    }

    public PostListResponse<PublicSummary> publicPosts(PostListQuery query) {
        var rows = rows(query, null);
        return new PostListResponse<>(
                rows.stream().limit(10).map(this::publicSummary).toList(), page(rows, query, null));
    }

    public PostListResponse<OwnSummary> ownPosts(PostListQuery query, long ownerId) {
        var rows = rows(query, ownerId);
        return new PostListResponse<>(
                rows.stream().limit(10).map(this::ownSummary).toList(), page(rows, query, ownerId));
    }

    private List<Row> rows(PostListQuery query, Long ownerId) {
        var position = cursors.decode(query.cursor(), query, ownerId);
        try {
            return posts.find(query, ownerId, position);
        } catch (DataAccessException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private Page page(List<Row> rows, PostListQuery query, Long ownerId) {
        if (rows.size() <= 10) return new Page(10, false, null);
        var last = rows.get(9);
        return new Page(
                10,
                true,
                cursors.encode(new Position(last.listedAt(), last.postId()), query, ownerId));
    }

    private PublicSummary publicSummary(Row row) {
        return new PublicSummary(
                row.postId(),
                row.type(),
                row.source(),
                row.name(),
                row.species(),
                row.breedName(),
                row.sex(),
                row.color(),
                row.eventDate(),
                row.listedAt(),
                row.publicLocation(),
                thumbnail(row));
    }

    private OwnSummary ownSummary(Row row) {
        return new OwnSummary(
                row.postId(),
                row.type(),
                row.source(),
                row.status(),
                row.version(),
                row.name(),
                row.species(),
                row.breedName(),
                row.sex(),
                row.color(),
                row.eventDate(),
                row.listedAt(),
                row.publicLocation(),
                thumbnail(row),
                row.updatedAt());
    }

    private String thumbnail(Row row) {
        if (row.photoId() == null) return null;
        if (row.source().equals("USER_POST"))
            return "USER_UPLOAD".equals(row.storageType())
                    ? "/api/v1/photos/" + row.photoId()
                    : null;
        if (!"PUBLIC_URL".equals(row.storageType())) return null;
        return PublicPhotoUrl.sanitize(row.storageUri());
    }
}
