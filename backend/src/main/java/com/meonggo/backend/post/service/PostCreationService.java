package com.meonggo.backend.post.service;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.photo.image.PhotoNormalizer;
import com.meonggo.backend.photo.service.PhotoWriteService;
import com.meonggo.backend.post.dto.CreatePostRequest;
import com.meonggo.backend.post.dto.CreatePostRequest.LocationInput;
import com.meonggo.backend.post.dto.CreatePostResponse;
import com.meonggo.backend.post.entity.AnimalCase;
import com.meonggo.backend.post.entity.AnimalCaseLocation;
import com.meonggo.backend.post.entity.LocationType;
import com.meonggo.backend.post.entity.UserPost;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.location.LocationProtection;
import com.meonggo.backend.post.repository.PostCreationRepository;
import com.meonggo.backend.post.repository.PostCreationRepository.ExistingPost;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 외부 저장이 끝난 뒤에만 회원 잠금과 DB 트랜잭션을 시작한다. */
@Service
public class PostCreationService {
    private final PostCreationRepository posts;
    private final PostAggregateService aggregates;
    private final PhotoNormalizer normalizer;
    private final PhotoWriteService photos;
    private final LocationProtection protection;

    public PostCreationService(
            PostCreationRepository posts,
            PostAggregateService aggregates,
            PhotoNormalizer normalizer,
            PhotoWriteService photos,
            LocationProtection protection) {
        this.posts = posts;
        this.aggregates = aggregates;
        this.normalizer = normalizer;
        this.photos = photos;
        this.protection = protection;
    }

    public CreatePostResponse create(
            long memberId, CreatePostRequest request, List<MultipartFile> inputs) {
        try {
            return createValidated(memberId, request, inputs);
        } catch (DataAccessException ex) {
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private CreatePostResponse createValidated(
            long memberId, CreatePostRequest request, List<MultipartFile> inputs) {
        posts.requireActive(memberId, false);
        var normalized = normalizer.normalize(inputs);
        String hash = PostRequestHash.calculate(request, normalized);
        var previous = posts.find(memberId, request.clientRequestId());
        if (previous.isPresent()) return replay(previous.get(), hash);
        long id = aggregates.reservePostId();
        try {
            return photos.write(
                    id,
                    normalized,
                    prepared -> {
                        posts.requireActive(memberId, true);
                        if (posts.find(memberId, request.clientRequestId()).isPresent())
                            throw new ConcurrentReplay();
                        var now = Instant.now();
                        var animal = AnimalCase.user(id, request.type(), request.details(), now);
                        var locations = new ArrayList<AnimalCaseLocation>();
                        locations.add(
                                location(id, LocationType.EVENT, request.eventLocation(), now));
                        if (request.currentLocation() != null)
                            locations.add(
                                    location(
                                            id,
                                            LocationType.CURRENT,
                                            request.currentLocation(),
                                            now));
                        aggregates.persist(
                                animal,
                                new UserPost(id, memberId, request.clientRequestId(), hash),
                                locations,
                                prepared);
                        // DB의 마이크로초 정밀도 값을 반환해 최초와 재시도의 createdAt을 일치시킨다.
                        return posts.find(memberId, request.clientRequestId())
                                .orElseThrow()
                                .response();
                    });
        } catch (ConcurrentReplay ex) {
            // PhotoWriteService가 rollback·신규 파일 회수를 끝낸 뒤 성공 행을 재조회한다.
            posts.requireActive(memberId, false);
            return replay(
                    posts.find(memberId, request.clientRequestId())
                            .orElseThrow(
                                    () ->
                                            new BusinessException(
                                                    CommonErrorCode.INTERNAL_SERVER_ERROR)),
                    hash);
        } catch (BusinessException ex) {
            // Unique 제약 경합/commit 응답 유실에도 실제 성공 행이 있으면 멱등 계약으로 수렴한다.
            if (ex.errorCode() != CommonErrorCode.INTERNAL_SERVER_ERROR) throw ex;
            posts.requireActive(memberId, false);
            var committed = posts.find(memberId, request.clientRequestId());
            if (committed.isPresent()) return replay(committed.get(), hash);
            throw ex;
        }
    }

    private CreatePostResponse replay(ExistingPost existing, String hash) {
        if (!existing.hash().equals(hash))
            throw new BusinessException(PostErrorCode.IDEMPOTENCY_CONFLICT);
        return existing.response();
    }

    private AnimalCaseLocation location(
            long id, LocationType role, LocationInput input, Instant now) {
        return new AnimalCaseLocation(
                id,
                role,
                input.regionCode(),
                input.emdCode(),
                input.publicLocation(),
                input.exactLocation() == null ? null : protection.encrypt(input.exactLocation()),
                input.exactLocationVisible(),
                input.disclosurePolicyVersion(),
                input.exactLocationVisible() ? now : null,
                input.latitude(),
                input.longitude());
    }

    private static final class ConcurrentReplay extends BusinessException {
        private ConcurrentReplay() {
            super(PostErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }
}
