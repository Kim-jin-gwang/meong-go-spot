package com.meonggo.backend.chat.query;

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

/** 커서는 페이지 위치이며 권한 증명이 아니다. 모든 조회에서 참여자 조건을 재검증한다. */
@Component
public class ChatCursorCodec {
    private static final int BYTES = 53;
    private static final long MIN_SECONDS = Instant.parse("0001-01-01T00:00:00Z").getEpochSecond();
    private static final long MAX_SECONDS = Instant.parse("9999-12-31T23:59:59Z").getEpochSecond();

    public Position decode(MultiValueMap<String, String> parameters, long memberId, long roomId) {
        if (parameters.entrySet().stream()
                .anyMatch(
                        entry ->
                                !"cursor".equals(entry.getKey()) || entry.getValue().size() != 1)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT);
        }
        String cursor = parameters.getFirst("cursor");
        if (cursor == null) return null;
        try {
            if (cursor.length() != 71 || !cursor.matches("[A-Za-z0-9_-]+")) throw invalid();
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (bytes.length != BYTES
                    || !Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(bytes)
                            .equals(cursor)) throw invalid();
            var buffer = ByteBuffer.wrap(bytes);
            if (buffer.get() != 1) throw invalid();
            long seconds = buffer.getLong();
            int nanos = buffer.getInt();
            long id = buffer.getLong();
            byte[] actualScope = new byte[32];
            buffer.get(actualScope);
            if (seconds < MIN_SECONDS
                    || seconds > MAX_SECONDS
                    || nanos < 0
                    || nanos >= 1_000_000_000
                    || nanos % 1000 != 0
                    || id <= 0
                    || !MessageDigest.isEqual(actualScope, scope(memberId, roomId)))
                throw invalid();
            return new Position(Instant.ofEpochSecond(seconds, nanos), id);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    public String encode(Position position, long memberId, long roomId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        ByteBuffer.allocate(BYTES)
                                .put((byte) 1)
                                .putLong(position.timestamp().getEpochSecond())
                                .putInt(position.timestamp().getNano())
                                .putLong(position.id())
                                .put(scope(memberId, roomId))
                                .array());
    }

    private byte[] scope(long memberId, long roomId) {
        try {
            // 0은 방 목록, 양수는 해당 방 메시지 목록. 게시물 커서와도 별도 도메인을 사용한다.
            return MessageDigest.getInstance("SHA-256")
                    .digest(
                            ByteBuffer.allocate(20)
                                    .putInt(0x43484154)
                                    .putLong(memberId)
                                    .putLong(roomId)
                                    .array());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Chat cursor digest is unavailable");
        }
    }

    private BusinessException invalid() {
        return new BusinessException(PostErrorCode.INVALID_CURSOR);
    }

    public record Position(Instant timestamp, long id) {}
}
