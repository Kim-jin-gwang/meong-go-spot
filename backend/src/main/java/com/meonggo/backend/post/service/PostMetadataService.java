package com.meonggo.backend.post.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.post.dto.UpdatePostResponse;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.repository.PostMetadataRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
public class PostMetadataService {
    private final PostMutationGuard guard;
    private final PostMetadataRepository posts;
    private final PostMetadataInput input;
    private final TransactionTemplate writes;

    public PostMetadataService(
            PostMutationGuard guard,
            PostMetadataRepository posts,
            PostMetadataInput input,
            PlatformTransactionManager transactions) {
        this.guard = guard;
        this.posts = posts;
        this.input = input;
        writes = new TransactionTemplate(transactions);
        writes.setTimeout(30);
    }

    public UpdatePostResponse update(long postId, long memberId, JsonNode patch) {
        input.validateFields(patch);
        long version = PostMutationInput.version(patch);
        try {
            return writes.execute(
                    transaction -> {
                        var target = guard.requireEditable(postId, memberId, version, true);
                        var stored = posts.content(postId);
                        var merged =
                                input.merge(
                                        patch,
                                        target.type(),
                                        stored.details(),
                                        posts.locations(postId),
                                        Instant.now().truncatedTo(ChronoUnit.MICROS));
                        if (!merged.changed())
                            return new UpdatePostResponse(postId, version, stored.updatedAt());
                        if (merged.contentChanged() && version == Long.MAX_VALUE)
                            throw new BusinessException(PostErrorCode.VERSION_CONFLICT);
                        long nextVersion = merged.contentChanged() ? version + 1 : version;
                        var response = posts.update(postId, version, nextVersion, merged.details());
                        merged.changedLocations()
                                .forEach(location -> posts.updateLocation(postId, location));
                        return response;
                    });
        } catch (DataAccessException | TransactionException | IllegalStateException exception) {
            // SQL/commit/crypto exception causes may contain protected values.
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
