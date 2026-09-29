package com.meonggo.backend.adoption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.adoption.query.AdoptionCursorCodec;
import com.meonggo.backend.adoption.query.AdoptionCursorCodec.Position;
import com.meonggo.backend.adoption.query.AdoptionListQuery;
import com.meonggo.backend.global.error.BusinessException;
import com.meonggo.backend.post.entity.Sex;
import com.meonggo.backend.post.entity.Species;
import com.meonggo.backend.post.exception.PostErrorCode;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AdoptionCursorCodecTest {
    private final AdoptionCursorCodec codec = new AdoptionCursorCodec();
    private final LocalDate asOfDate = LocalDate.of(2026, 9, 17);
    private final AdoptionListQuery query = new AdoptionListQuery("11680", Species.DOG, null, null);
    private final Position position = new Position(LocalDate.of(2026, 3, 20), Long.MAX_VALUE);

    @Test
    void roundTripsPositionAndBindsDateAndFilters() {
        String cursor = codec.encode(position, query, asOfDate);

        assertThat(codec.decode(cursor, query, asOfDate)).isEqualTo(position);
        assertThat(codec.decode(null, query, asOfDate)).isNull();
        assertThat(cursor).doesNotContain("=", "11680", "DOG", "2026");

        reject(cursor, new AdoptionListQuery("26110", Species.DOG, null, cursor), asOfDate);
        reject(cursor, new AdoptionListQuery("11680", Species.CAT, null, cursor), asOfDate);
        reject(cursor, new AdoptionListQuery("11680", Species.DOG, Sex.MALE, cursor), asOfDate);
        reject(cursor, query, asOfDate.plusDays(1));
    }

    @Test
    void rejectsMalformedBoundsAndNoncanonicalEncoding() {
        String valid = codec.encode(position, query, asOfDate);
        for (Consumer<ByteBuffer> corrupt :
                List.<Consumer<ByteBuffer>>of(
                        bytes -> bytes.put(0, (byte) 2),
                        bytes -> bytes.putLong(1, Long.MIN_VALUE),
                        bytes -> bytes.putLong(9, asOfDate.toEpochDay()),
                        bytes -> bytes.putLong(17, 0),
                        bytes -> bytes.putLong(17, -1),
                        bytes -> bytes.put(25, (byte) (bytes.get(25) ^ 1)))) {
            byte[] decoded = Base64.getUrlDecoder().decode(valid);
            var bytes = ByteBuffer.wrap(decoded);
            corrupt.accept(bytes);
            reject(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array()),
                    query,
                    asOfDate);
        }

        for (String invalid :
                List.of(
                        "",
                        "a".repeat(1_000_000),
                        valid + "=",
                        valid.substring(1),
                        "!" + valid.substring(1))) {
            reject(invalid, query, asOfDate);
        }
    }

    private void reject(String cursor, AdoptionListQuery actualQuery, LocalDate actualDate) {
        assertThatThrownBy(() -> codec.decode(cursor, actualQuery, actualDate))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.errorCode())
                                        .isEqualTo(PostErrorCode.INVALID_CURSOR));
    }
}
