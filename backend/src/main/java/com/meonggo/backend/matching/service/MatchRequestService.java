package com.meonggo.backend.matching.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.matching.config.MatchingConfiguration;
import com.meonggo.backend.matching.dto.MatchRequestResponse;
import com.meonggo.backend.matching.repository.MatchRequestRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MatchRequestService {
    private final MatchAccessGuard guard;
    private final MatchRequestRepository requests;
    private final MatchingConfiguration configuration;
    private final TransactionTemplate transaction;

    public MatchRequestService(
            MatchAccessGuard guard,
            MatchRequestRepository requests,
            MatchingConfiguration configuration,
            PlatformTransactionManager manager) {
        this.guard = guard;
        this.requests = requests;
        this.configuration = configuration;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(30);
    }

    public MatchRequestResponse request(long postId, long memberId) {
        try {
            return transaction.execute(
                    status -> {
                        var post = guard.requireEligible(postId, memberId, true);
                        return requests.findActive(postId)
                                .orElseGet(
                                        () ->
                                                requests.insert(
                                                        postId,
                                                        post.version(),
                                                        configuration.modelId(),
                                                        configuration.modelVersion()));
                    });
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
