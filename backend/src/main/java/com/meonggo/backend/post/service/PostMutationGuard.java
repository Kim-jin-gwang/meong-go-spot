package com.meonggo.backend.post.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.repository.PostCreationRepository;
import com.meonggo.backend.post.repository.PostMutationRepository;
import com.meonggo.backend.post.repository.PostMutationRepository.Target;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 회원 → 게시물 순서의 잠금과 요청 버전 검증을 모든 변경 API에서 공유한다. */
@Service
public class PostMutationGuard {
    private final PostCreationRepository members;
    private final PostMutationRepository posts;

    public PostMutationGuard(PostCreationRepository members, PostMutationRepository posts) {
        this.members = members;
        this.posts = posts;
    }

    public Target requireEditable(long postId, long memberId, long version, boolean lock) {
        if (lock && !TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Post mutation requires a transaction");
        members.requireActive(memberId, lock);
        Target target =
                posts.find(postId, lock)
                        .orElseThrow(() -> new BusinessException(PostErrorCode.NOT_FOUND));
        if ("PUBLIC".equals(target.source()))
            throw new BusinessException(PostErrorCode.PUBLIC_IMMUTABLE);
        if (target.ownerId() == null || target.ownerId() != memberId)
            throw new BusinessException(PostErrorCode.NOT_OWNER);
        if (!"ACTIVE".equals(target.status()))
            throw new BusinessException(PostErrorCode.NOT_ACTIVE);
        if (target.version() != version)
            throw new BusinessException(PostErrorCode.VERSION_CONFLICT);
        return target;
    }
}
