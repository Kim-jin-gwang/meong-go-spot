package com.meonggo.backend.post.service;

import com.meonggo.backend.global.common.photo.PublicPhotoUrl;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.post.dto.PostDetailResponse;
import com.meonggo.backend.post.dto.PostDetailResponse.AuthorResponse;
import com.meonggo.backend.post.dto.PostDetailResponse.ChatResponse;
import com.meonggo.backend.post.dto.PostDetailResponse.LocationResponse;
import com.meonggo.backend.post.dto.PostDetailResponse.PublicLostResponse;
import com.meonggo.backend.post.dto.PostDetailResponse.PublicPostResponse;
import com.meonggo.backend.post.dto.PostDetailResponse.UserPostResponse;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.location.LocationProtection;
import com.meonggo.backend.post.repository.PostDetailRepository;
import com.meonggo.backend.post.repository.PostDetailRepository.LocationProjection;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PostDetailService {
    private final PostDetailRepository posts;
    private final LocationProtection protection;
    private final TransactionTemplate reads;

    public PostDetailService(
            PostDetailRepository posts,
            LocationProtection protection,
            PlatformTransactionManager transactions) {
        this.posts = posts;
        this.protection = protection;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** viewerId가 null이면 비로그인 조회다 — 비소유자와 같되 정확한 위치는 절대 싣지 않는다. */
    public PostDetailResponse detail(long postId, Long viewerId) {
        if (postId <= 0) throw new BusinessException(PostErrorCode.NOT_FOUND);
        try {
            return reads.execute(transaction -> read(postId, viewerId));
        } catch (DataAccessException | TransactionException exception) {
            // SQL/커밋 예외의 원문에는 보호된 데이터가 들어갈 수 있다.
            throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private PostDetailResponse read(long postId, Long viewerId) {
        var post =
                posts.find(postId)
                        .orElseThrow(() -> new BusinessException(PostErrorCode.NOT_FOUND));
        boolean user = "USER".equals(post.source());
        boolean owner = user && viewerId != null && viewerId.equals(post.memberId());
        boolean active = "ACTIVE".equals(post.status());
        // 정확한 위치는 로그인 상세에서만 공개한다 (post-date-location-policy.md).
        var locations = posts.locations(postId, owner, user && active && viewerId != null);
        var event = location(locations, "EVENT");
        var current = "SHELTERING".equals(post.type()) ? location(locations, "CURRENT") : null;
        var photos =
                user && !active && !owner
                        ? List.<PostDetailResponse.PhotoResponse>of()
                        : posts.photos(postId, user).stream()
                                .map(photo -> user ? photo : publicPhoto(photo))
                                .filter(Objects::nonNull)
                                .toList();
        if (user) {
            var chat =
                    new ChatResponse(
                            active && !owner,
                            !active ? "POST_NOT_ACTIVE" : owner ? "OWN_POST" : null);
            return new UserPostResponse(
                    post.id(),
                    post.type(),
                    "USER_POST",
                    post.status(),
                    post.version(),
                    post.name(),
                    post.species(),
                    post.breedName(),
                    post.sex(),
                    post.color(),
                    post.eventDate(),
                    post.eventTime(),
                    post.featureText(),
                    event,
                    current,
                    photos,
                    new AuthorResponse(post.memberId(), post.nickname()),
                    chat,
                    owner,
                    post.createdAt(),
                    post.updatedAt());
        }
        if ("LOST".equals(post.type())) {
            // 공공 분실 신고 — 보호소가 없고 신고자 연락처는 저장하지 않는다. CURRENT 위치도 없다.
            return new PublicLostResponse(
                    post.id(),
                    post.type(),
                    "PUBLIC_LOST",
                    post.status(),
                    post.name(),
                    post.species(),
                    post.breedName(),
                    post.sex(),
                    post.color(),
                    post.eventDate(),
                    post.eventTime(),
                    post.featureText(),
                    event,
                    photos,
                    // 포털 링크는 실종일·축종·성별·시·도로 좁힌 목록이다 — 원천에 건별 식별자가 없다
                    post.lostReport() == null
                            ? null
                            : post.lostReport()
                                    .withPortal(
                                            post.eventDate(),
                                            post.species(),
                                            post.sex(),
                                            event == null ? null : event.publicLocation()),
                    post.createdAt(),
                    post.updatedAt());
        }
        return new PublicPostResponse(
                post.id(),
                post.type(),
                "SHELTER",
                post.status(),
                post.name(),
                post.species(),
                post.breedName(),
                post.sex(),
                post.color(),
                post.eventDate(),
                post.eventTime(),
                post.featureText(),
                event,
                current,
                photos,
                post.shelter(),
                post.createdAt(),
                post.updatedAt());
    }

    private LocationResponse location(List<LocationProjection> locations, String role) {
        var location =
                locations.stream()
                        .filter(value -> role.equals(value.role()))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(PostErrorCode.NOT_FOUND));
        String exact = null;
        if (location.permittedCiphertext() != null) {
            try {
                exact = protection.decrypt(location.permittedCiphertext());
            } catch (IllegalStateException exception) {
                throw new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
            }
            if (exact.isBlank()) exact = null;
        }
        return new LocationResponse(
                location.regionCode(),
                location.emdCode(),
                location.publicLocation(),
                exact,
                location.ownerVisibility());
    }

    /** 공공 사진은 앱에 넘길 수 있는 형태로 정규화해서 싣는다. 넘길 수 없으면 목록에서 뺀다. */
    private static PostDetailResponse.PhotoResponse publicPhoto(
            PostDetailResponse.PhotoResponse photo) {
        String url = PublicPhotoUrl.sanitize(photo.url());
        return url == null
                ? null
                : new PostDetailResponse.PhotoResponse(photo.photoId(), url, photo.sortOrder());
    }
}
