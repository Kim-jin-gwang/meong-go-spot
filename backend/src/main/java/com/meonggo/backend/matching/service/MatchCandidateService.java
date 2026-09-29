package com.meonggo.backend.matching.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.matching.dto.MatchCandidatesResponse;
import com.meonggo.backend.matching.repository.MatchResultRepository;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MatchCandidateService {
    private final MatchAccessGuard access;
    private final MatchResultRepository results;
    private final TransactionTemplate reads;

    public MatchCandidateService(
            MatchAccessGuard access,
            MatchResultRepository results,
            PlatformTransactionManager transactions) {
        this.access = access;
        this.results = results;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(30);
    }

    public MatchCandidatesResponse candidates(long postId, long memberId) {
        try {
            return reads.execute(transaction -> read(postId, memberId));
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private MatchCandidatesResponse read(long postId, long memberId) {
        var post = access.requireEligible(postId, memberId, false);
        var latest = results.latest(postId).orElse(null);
        if (latest == null) {
            return new MatchCandidatesResponse(
                    postId, "NOT_REQUESTED", null, null, false, null, List.of());
        }
        var success = results.latestSuccess(postId).orElse(null);
        String status = latest.summary().status();
        Integer pollAfter = "PENDING".equals(status) || "RUNNING".equals(status) ? 1000 : null;
        boolean stale = success != null && success.queryCaseVersion() < post.version();
        return new MatchCandidatesResponse(
                postId,
                stale ? "STALE" : status,
                latest.summary(),
                success == null ? null : success.id(),
                success != null && success.id() != latest.id(),
                pollAfter,
                success == null ? List.of() : results.candidates(success.id()));
    }
}
