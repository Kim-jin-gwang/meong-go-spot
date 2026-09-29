package com.meonggo.backend.post.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.photo.image.PhotoNormalizer;
import com.meonggo.backend.photo.service.PhotoWriteService;
import com.meonggo.backend.post.dto.PostPhotoReplacementResponse;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.repository.PostLifecycleRepository;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PostPhotoReplacementService {
    private final PostMutationGuard guard;
    private final PostLifecycleRepository posts;
    private final PhotoNormalizer normalizer;
    private final PhotoWriteService photos;

    public PostPhotoReplacementService(
            PostMutationGuard guard,
            PostLifecycleRepository posts,
            PhotoNormalizer normalizer,
            PhotoWriteService photos) {
        this.guard = guard;
        this.posts = posts;
        this.normalizer = normalizer;
        this.photos = photos;
    }

    public PostPhotoReplacementResponse replace(
            long postId, long memberId, long version, List<MultipartFile> inputs) {
        try {
            guard.requireEditable(postId, memberId, version, false);
            if (version == Long.MAX_VALUE)
                throw new BusinessException(PostErrorCode.VERSION_CONFLICT);
            var normalized = normalizer.normalize(inputs);
            return photos.write(
                    postId,
                    normalized,
                    prepared -> {
                        guard.requireEditable(postId, memberId, version, true);
                        return posts.replace(postId, version, prepared);
                    });
        } catch (DataAccessException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
