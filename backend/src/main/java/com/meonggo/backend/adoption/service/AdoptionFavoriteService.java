package com.meonggo.backend.adoption.service;

import com.meonggo.backend.adoption.dto.AdoptionFavoriteListResponse;
import com.meonggo.backend.adoption.dto.AdoptionFavoriteListResponse.Availability;
import com.meonggo.backend.adoption.dto.AdoptionFavoriteListResponse.Item;
import com.meonggo.backend.adoption.dto.AdoptionFavoriteListResponse.Page;
import com.meonggo.backend.adoption.exception.AdoptionErrorCode;
import com.meonggo.backend.adoption.query.AdoptionFavoriteCursorCodec;
import com.meonggo.backend.adoption.query.AdoptionFavoriteCursorCodec.Position;
import com.meonggo.backend.adoption.repository.AdoptionFavoriteRepository;
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

@Service
public class AdoptionFavoriteService {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final int PAGE_SIZE = 10;

    private final AdoptionFavoriteRepository favorites;
    private final AdoptionFavoriteCursorCodec cursors;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AdoptionFavoriteService(
            AdoptionFavoriteRepository favorites,
            AdoptionFavoriteCursorCodec cursors,
            @Qualifier("dataSourceClock") Clock clock,
            PlatformTransactionManager transactions) {
        this.favorites = favorites;
        this.cursors = cursors;
        this.clock = clock;
        transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(30);
    }

    @Transactional(readOnly = true)
    public AdoptionFavoriteListResponse list(
            long memberId, MultiValueMap<String, String> parameters) {
        LocalDate asOfDate = asOfDate();
        Position position = cursors.decode(parameters, memberId);
        try {
            var rows = favorites.find(memberId, asOfDate, position, PAGE_SIZE + 1);
            var items = rows.stream().limit(PAGE_SIZE).map(row -> item(row, asOfDate)).toList();
            boolean hasNext = rows.size() > PAGE_SIZE;
            String nextCursor = null;
            if (hasNext) {
                var last = rows.get(PAGE_SIZE - 1);
                nextCursor =
                        cursors.encode(new Position(last.favoritedAt(), last.postId()), memberId);
            }
            return new AdoptionFavoriteListResponse(
                    asOfDate, items, new Page(PAGE_SIZE, hasNext, nextCursor));
        } catch (DataAccessException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public void add(long memberId, long postId) {
        validate(postId);
        execute(
                () -> {
                    if (favorites.exists(memberId, postId)) return;
                    String photoUrl =
                            favorites
                                    .findEligiblePhoto(postId, asOfDate())
                                    .map(PublicPhotoUrl::sanitize)
                                    .orElse(null);
                    if (photoUrl == null) {
                        throw new BusinessException(AdoptionErrorCode.CANDIDATE_NOT_FOUND);
                    }
                    favorites.insert(memberId, postId);
                });
    }

    public void remove(long memberId, long postId) {
        validate(postId);
        execute(() -> favorites.delete(memberId, postId));
    }

    private Item item(AdoptionFavoriteRepository.Row row, LocalDate asOfDate) {
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
                row.favoritedAt(),
                available ? Availability.AVAILABLE : Availability.UNAVAILABLE);
    }

    private void execute(Runnable action) {
        try {
            transaction.executeWithoutResult(status -> action.run());
        } catch (BusinessException exception) {
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
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
