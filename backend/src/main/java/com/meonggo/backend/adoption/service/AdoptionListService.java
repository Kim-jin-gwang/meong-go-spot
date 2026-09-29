package com.meonggo.backend.adoption.service;

import com.meonggo.backend.adoption.dto.AdoptionListResponse;
import com.meonggo.backend.adoption.dto.AdoptionListResponse.Item;
import com.meonggo.backend.adoption.dto.AdoptionListResponse.Page;
import com.meonggo.backend.adoption.query.AdoptionCursorCodec;
import com.meonggo.backend.adoption.query.AdoptionCursorCodec.Position;
import com.meonggo.backend.adoption.query.AdoptionListQuery;
import com.meonggo.backend.adoption.repository.AdoptionListRepository;
import com.meonggo.backend.global.common.photo.PublicPhotoUrl;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdoptionListService {
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final int PAGE_SIZE = 10;
    private static final int SCAN_SIZE = 50;

    private final AdoptionListRepository candidates;
    private final AdoptionCursorCodec cursors;
    private final Clock clock;

    public AdoptionListService(
            AdoptionListRepository candidates,
            AdoptionCursorCodec cursors,
            @Qualifier("dataSourceClock") Clock clock) {
        this.candidates = candidates;
        this.cursors = cursors;
        this.clock = clock;
    }

    @Transactional
    public AdoptionListResponse list(AdoptionListQuery query, Long memberId) {
        LocalDate asOfDate = clock.instant().atZone(SERVICE_ZONE).toLocalDate();
        Position position = cursors.decode(query.cursor(), query, asOfDate);
        List<SafeRow> safeRows = safeRows(query, asOfDate, memberId, position);
        List<Item> items =
                safeRows.stream().limit(PAGE_SIZE).map(row -> item(row, asOfDate)).toList();
        boolean hasNext = safeRows.size() > PAGE_SIZE;
        String nextCursor = null;
        if (hasNext) {
            SafeRow last = safeRows.get(PAGE_SIZE - 1);
            nextCursor =
                    cursors.encode(
                            new Position(last.row().noticeEndDate(), last.row().postId()),
                            query,
                            asOfDate);
        }
        return new AdoptionListResponse(asOfDate, items, new Page(PAGE_SIZE, hasNext, nextCursor));
    }

    private List<SafeRow> safeRows(
            AdoptionListQuery query, LocalDate asOfDate, Long memberId, Position initialPosition) {
        var safeRows = new ArrayList<SafeRow>(PAGE_SIZE + 1);
        Position scanPosition = initialPosition;
        try {
            while (safeRows.size() <= PAGE_SIZE) {
                var rows = candidates.find(query, asOfDate, memberId, scanPosition, SCAN_SIZE);
                for (var row : rows) {
                    scanPosition = new Position(row.noticeEndDate(), row.postId());
                    String photoUrl = PublicPhotoUrl.sanitize(row.photoUrl());
                    if (photoUrl != null) safeRows.add(new SafeRow(row, photoUrl));
                    if (safeRows.size() > PAGE_SIZE) break;
                }
                if (safeRows.size() > PAGE_SIZE || rows.size() < SCAN_SIZE) break;
            }
            return safeRows;
        } catch (DataAccessException exception) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private Item item(SafeRow safe, LocalDate asOfDate) {
        var row = safe.row();
        return new Item(
                row.postId(),
                row.species(),
                row.breedName(),
                row.sex(),
                row.color(),
                row.publicLocation(),
                safe.photoUrl(),
                row.noticeEndDate(),
                ChronoUnit.DAYS.between(row.noticeEndDate(), asOfDate),
                row.lastSyncedAt(),
                row.favorited());
    }

    private record SafeRow(AdoptionListRepository.Row row, String photoUrl) {}
}
