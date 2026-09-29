package com.meonggo.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.entity.CaseType;
import com.meonggo.backend.post.exception.PostErrorCode;
import com.meonggo.backend.post.query.PostCursorCodec;
import com.meonggo.backend.post.query.PostCursorCodec.Position;
import com.meonggo.backend.post.query.PostListQuery;
import com.meonggo.backend.post.query.PostListSort;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PostCursorCodecTest {
    private final PostCursorCodec codec = new PostCursorCodec();
    private final PostListQuery query =
            new PostListQuery(
                    CaseType.LOST,
                    null,
                    null,
                    null,
                    "11680",
                    null,
                    null,
                    null,
                    PostListSort.LATEST,
                    null);
    private final Position position =
            new Position(Instant.parse("2026-01-01T00:00:00.123456Z"), Long.MAX_VALUE);

    @Test
    void preservesDatabaseMicrosecondsAndFullPositiveIdRange() {
        String cursor = codec.encode(position, query, null);
        assertThat(codec.decode(cursor, query, null)).isEqualTo(position);
        assertThat(codec.decode(null, query, null)).isNull();
        assertThat(cursor).doesNotContain("=", "11680");
    }

    @Test
    void rejectsMalformedBinaryBoundariesAndNoncanonicalEncoding() {
        String valid = codec.encode(position, query, null);
        for (Consumer<ByteBuffer> corrupt :
                List.<Consumer<ByteBuffer>>of(
                        bytes -> bytes.put(0, (byte) 2),
                        bytes -> bytes.putLong(1, Long.MIN_VALUE),
                        bytes -> bytes.putLong(1, Long.MAX_VALUE),
                        bytes -> bytes.putInt(9, -1),
                        bytes -> bytes.putInt(9, 1_000_000_000),
                        bytes -> bytes.putInt(9, 1),
                        bytes -> bytes.putLong(13, 0),
                        bytes -> bytes.putLong(13, -1),
                        bytes -> bytes.put(21, (byte) (bytes.get(21) ^ 1)))) {
            var bytes = ByteBuffer.wrap(Base64.getUrlDecoder().decode(valid));
            corrupt.accept(bytes);
            reject(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array()));
        }
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last = alphabet.indexOf(valid.charAt(valid.length() - 1));
        reject(valid.substring(0, valid.length() - 1) + alphabet.charAt(last + 1));
        for (String invalid :
                List.of(
                        "",
                        "a".repeat(1_000_000),
                        valid + "=",
                        valid.substring(1),
                        "!" + valid.substring(1))) reject(invalid);
    }

    private void reject(String cursor) {
        assertThatThrownBy(() -> codec.decode(cursor, query, null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(PostErrorCode.INVALID_CURSOR));
    }
}
