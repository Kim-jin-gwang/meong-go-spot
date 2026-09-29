package com.meonggo.backend.post.query;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.exception.PostErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** 커서는 조회 위치일 뿐 권한 증명이 아니다. Repository가 모든 공개·작성자 조건을 재적용한다. */
@Component
public class PostCursorCodec {
    private static final int BYTES = 53;
    private static final long MIN_SECONDS = Instant.parse("0001-01-01T00:00:00Z").getEpochSecond();
    private static final long MAX_SECONDS = Instant.parse("9999-12-31T23:59:59Z").getEpochSecond();

    public Position decode(String cursor, PostListQuery query, Long ownerId) {
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
            if (seconds < MIN_SECONDS
                    || seconds > MAX_SECONDS
                    || nanos < 0
                    || nanos >= 1_000_000_000
                    || nanos % 1000 != 0
                    || id <= 0) throw invalid();
            byte[] scope = new byte[32];
            buffer.get(scope);
            if (!MessageDigest.isEqual(scope, scope(query, ownerId))) throw invalid();
            return new Position(Instant.ofEpochSecond(seconds, nanos), id);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    public String encode(Position position, PostListQuery query, Long ownerId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        ByteBuffer.allocate(BYTES)
                                .put((byte) 1)
                                .putLong(position.listedAt().getEpochSecond())
                                .putInt(position.listedAt().getNano())
                                .putLong(position.postId())
                                .put(scope(query, ownerId))
                                .array());
    }

    private byte[] scope(PostListQuery query, Long ownerId) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                for (Object field :
                        Arrays.asList(
                                ownerId,
                                query.type(),
                                query.species(),
                                query.sex(),
                                query.breedName(),
                                query.regionCode(),
                                query.color(),
                                query.source(),
                                query.status(),
                                query.sort())) {
                    if (field == null) {
                        out.writeInt(-1);
                        continue;
                    }
                    byte[] encoded = field.toString().getBytes(StandardCharsets.UTF_8);
                    out.writeInt(encoded.length);
                    out.write(encoded);
                }
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("목록 커서를 처리할 수 없습니다.");
        }
    }

    private BusinessException invalid() {
        return new BusinessException(PostErrorCode.INVALID_CURSOR);
    }

    public record Position(Instant listedAt, long postId) {}
}
