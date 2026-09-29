package com.meonggo.backend.adoption.query;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.global.error.CommonErrorCode;
import com.meonggo.backend.post.exception.PostErrorCode;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/** 넘김 목록 커서는 회원별 페이지 위치이며, 회원 접근 권한은 조회마다 다시 확인한다. */
@Component
public class AdoptionSwipeCursorCodec {
    private static final int BYTES = 53;
    private static final int ENCODED_LENGTH = 71;
    private static final byte VERSION = 1;
    private static final long MIN_SECONDS = Instant.parse("0001-01-01T00:00:00Z").getEpochSecond();
    private static final long MAX_SECONDS = Instant.parse("9999-12-31T23:59:59Z").getEpochSecond();

    public Position decode(MultiValueMap<String, String> parameters, long memberId) {
        if (parameters.entrySet().stream()
                .anyMatch(
                        entry ->
                                !"cursor".equals(entry.getKey())
                                        || entry.getValue().size() != 1
                                        || entry.getValue().getFirst() == null)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        String cursor = parameters.getFirst("cursor");
        if (cursor == null) return null;
        try {
            if (cursor.length() != ENCODED_LENGTH || !cursor.matches("[A-Za-z0-9_-]+")) {
                throw invalid();
            }
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (bytes.length != BYTES
                    || !Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(bytes)
                            .equals(cursor)) {
                throw invalid();
            }
            var buffer = ByteBuffer.wrap(bytes);
            if (buffer.get() != VERSION) throw invalid();
            long seconds = buffer.getLong();
            int nanos = buffer.getInt();
            long postId = buffer.getLong();
            byte[] actualScope = new byte[32];
            buffer.get(actualScope);
            if (seconds < MIN_SECONDS
                    || seconds > MAX_SECONDS
                    || nanos < 0
                    || nanos >= 1_000_000_000
                    || nanos % 1000 != 0
                    || postId <= 0
                    || !MessageDigest.isEqual(actualScope, scope(memberId))) {
                throw invalid();
            }
            return new Position(Instant.ofEpochSecond(seconds, nanos), postId);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    public String encode(Position position, long memberId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        ByteBuffer.allocate(BYTES)
                                .put(VERSION)
                                .putLong(position.swipedAt().getEpochSecond())
                                .putInt(position.swipedAt().getNano())
                                .putLong(position.postId())
                                .put(scope(memberId))
                                .array());
    }

    /** 찜 커서와 다른 도메인 상수를 써서 한쪽 커서를 다른 목록에 쓸 수 없게 한다. */
    private byte[] scope(long memberId) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(
                            ByteBuffer.allocate(24)
                                    .putLong(0x41444F5054535750L)
                                    .putLong(1L)
                                    .putLong(memberId)
                                    .array());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("입양 넘김 목록 커서를 처리할 수 없습니다.");
        }
    }

    private BusinessException invalid() {
        return new BusinessException(PostErrorCode.INVALID_CURSOR);
    }

    public record Position(Instant swipedAt, long postId) {}
}
