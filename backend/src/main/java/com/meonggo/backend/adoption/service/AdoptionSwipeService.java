package com.meonggo.backend.adoption.service;

import com.meonggo.backend.adoption.dto.AdoptionFavoriteListResponse.Availability;
import com.meonggo.backend.adoption.dto.AdoptionSwipeListResponse;
import com.meonggo.backend.adoption.dto.AdoptionSwipeListResponse.Item;
import com.meonggo.backend.adoption.dto.AdoptionSwipeListResponse.Page;
import com.meonggo.backend.adoption.exception.AdoptionErrorCode;
import com.meonggo.backend.adoption.query.AdoptionSwipeCursorCodec;
import com.meonggo.backend.adoption.query.AdoptionSwipeCursorCodec.Position;
import com.meonggo.backend.adoption.repository.AdoptionSwipeRepository;
import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.global.common.photo.PublicPhotoUrl;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

/**
 * AD5·AD6 — 넘긴 동물 기록과 목록.
 *
 * <p>넘긴 방향은 받지도 저장하지도 않는다. 찜 여부는 {@code adoption_favorite} 하나가 정답이고, 두 곳에 두면 찜을 해제했을 때 어긋난다 (erd.md
 * 2.23, 2026-09-25).
 */
@Service
public class AdoptionSwipeService {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final int PAGE_SIZE = 10;

    private final AdoptionSwipeRepository swipes;
    private final AdoptionSwipeCursorCodec cursors;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AdoptionSwipeService(
            AdoptionSwipeRepository swipes,
            AdoptionSwipeCursorCodec cursors,
            @Qualifier("dataSourceClock") Clock clock,
            PlatformTransactionManager transactions) {
        this.swipes = swipes;
        this.cursors = cursors;
        this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(30);
    }

    @Transactional(readOnly = true)
    public AdoptionSwipeListResponse list(long memberId, MultiValueMap<String, String> parameters) {
        LocalDate asOfDate = asOfDate();
        Position position = cursors.decode(parameters, memberId);
        try {
            var rows = swipes.find(memberId, asOfDate, position, PAGE_SIZE + 1);
            var items = rows.stream().limit(PAGE_SIZE).map(row -> item(row, asOfDate)).toList();
            boolean hasNext = rows.size() > PAGE_SIZE;
            String nextCursor = null;
            if (hasNext) {
                var last = rows.get(PAGE_SIZE - 1);
                nextCursor = cursors.encode(new Position(last.swipedAt(), last.postId()), memberId);
            }
            return new AdoptionSwipeListResponse(
                    asOfDate, items, new Page(PAGE_SIZE, hasNext, nextCursor));
        } catch (DataAccessException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public void record(long memberId, long postId) {
        validate(postId);
        try {
            transaction.executeWithoutResult(
                    status -> {
                        if (!swipes.isPublicShelteringCase(postId)) {
                            throw new BusinessException(AdoptionErrorCode.CANDIDATE_NOT_FOUND);
                        }
                        swipes.insert(memberId, postId, clock.instant());
                    });
        } catch (BusinessException exception) {
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private Item item(AdoptionSwipeRepository.Row row, LocalDate asOfDate) {
        String photoUrl = PublicPhotoUrl.sanitize(row.photoUrl());
        boolean available = row.databaseEligible() && photoUrl != null;
        Long daysSinceNoticeEnd =
                row.noticeEndDate() != null && row.noticeEndDate().isBefore(asOfDate)
                        ? ChronoUnit.DAYS.between(row.noticeEndDate(), asOfDate)
                        : null;
        return new Item(
                row.postId(),
                row.species(),
                row.breedName(),
                row.sex(),
                row.color(),
                row.publicLocation(),
                photoUrl,
                row.noticeEndDate(),
                daysSinceNoticeEnd,
                row.lastSyncedAt(),
                row.swipedAt(),
                row.favorited(),
                available ? Availability.AVAILABLE : Availability.UNAVAILABLE);
    }

    private LocalDate asOfDate() {
        return clock.instant().atZone(SERVICE_ZONE).toLocalDate();
    }

    private void validate(long postId) {
        if (postId <= 0) {
            throw new InputValidationException("postId", "게시물 식별자를 확인해 주세요.");
        }
    }
}
