package com.meonggo.backend.adoption.query;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.exception.PostErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** 입양 후보 커서는 페이지 위치이며, 모든 후보 자격과 필터는 조회마다 다시 적용한다. */
@Component
public class AdoptionCursorCodec {
    private static final int BYTES = 57;
    private static final int ENCODED_LENGTH = 76;
    private static final byte VERSION = 1;

    public Position decode(String cursor, AdoptionListQuery query, LocalDate asOfDate) {
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
            LocalDate encodedDate = LocalDate.ofEpochDay(buffer.getLong());
            LocalDate noticeEndDate = LocalDate.ofEpochDay(buffer.getLong());
            long postId = buffer.getLong();
            byte[] actualScope = new byte[32];
            buffer.get(actualScope);
            if (!encodedDate.equals(asOfDate)
                    || !noticeEndDate.isBefore(asOfDate)
                    || postId <= 0
                    || !MessageDigest.isEqual(actualScope, scope(query))) {
                throw invalid();
            }
            return new Position(noticeEndDate, postId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid();
        }
    }

    public String encode(Position position, AdoptionListQuery query, LocalDate asOfDate) {
        if (position == null
                || asOfDate == null
                || position.postId() <= 0
                || !position.noticeEndDate().isBefore(asOfDate)) {
            throw new IllegalArgumentException("Invalid adoption cursor position");
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        ByteBuffer.allocate(BYTES)
                                .put(VERSION)
                                .putLong(asOfDate.toEpochDay())
                                .putLong(position.noticeEndDate().toEpochDay())
                                .putLong(position.postId())
                                .put(scope(query))
                                .array());
    }

    private byte[] scope(AdoptionListQuery query) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                // 필터가 하나 늘면 도메인 상수도 올린다 — 예전 커서가 새 필터 조합에 통과하면 안 된다.
                output.write("ADOPTION_LIST_V2".getBytes(StandardCharsets.US_ASCII));
                write(output, query.regionCode());
                write(output, query.species() == null ? null : query.species().name());
                write(output, query.sex() == null ? null : query.sex().name());
            }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("입양 목록 커서를 처리할 수 없습니다.");
        }
    }

    private void write(DataOutputStream output, String value) throws java.io.IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private BusinessException invalid() {
        return new BusinessException(PostErrorCode.INVALID_CURSOR);
    }

    public record Position(LocalDate noticeEndDate, long postId) {}
}
