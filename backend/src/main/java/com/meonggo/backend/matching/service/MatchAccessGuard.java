package com.meonggo.backend.matching.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.matching.exception.MatchErrorCode;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.repository.PostCreationRepository;
import com.meonggo.backend.post.repository.PostMutationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class MatchAccessGuard {
    private final PostCreationRepository members;
    private final PostMutationRepository posts;

    public MatchAccessGuard(PostCreationRepository members, PostMutationRepository posts) {
        this.members = members;
        this.posts = posts;
    }

    public PostMutationRepository.Target requireEligible(long postId, long memberId, boolean lock) {
        if (lock && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Match access check requires a transaction");
        }
        members.requireActive(memberId, lock);
        var post =
                posts.find(postId, lock)
                        .orElseThrow(() -> new BusinessException(PostErrorCode.NOT_FOUND));
        if (!"USER".equals(post.source()) || !"LOST".equals(post.type().name())) {
            throw new BusinessException(MatchErrorCode.INELIGIBLE_POST);
        }
        if (!Long.valueOf(memberId).equals(post.ownerId())) {
            throw new BusinessException(PostErrorCode.NOT_OWNER);
        }
        if (!"ACTIVE".equals(post.status())) {
            throw new BusinessException(PostErrorCode.NOT_ACTIVE);
        }
        return post;
    }
}
