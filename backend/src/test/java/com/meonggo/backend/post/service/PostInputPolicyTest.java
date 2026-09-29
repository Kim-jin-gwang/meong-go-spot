package com.meonggo.backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.InputValidationException;
import com.meonggo.backend.post.location.RegionCodeCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PostInputPolicyTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void usesSeoulDateAtUtcDayBoundaryAndKeepsOptionalFutureTime() throws Exception {
        String body = body("2026-09-10", ",\"eventTime\":\"23:59:59\"");
        var before = policy("2026-09-09T14:59:59Z");
        assertThatThrownBy(() -> before.validate(mapper.readTree(body)))
                .isInstanceOf(InputValidationException.class);
        var after = policy("2026-09-09T15:00:00Z");
        assertThat(after.validate(mapper.readTree(body)).details().eventDate())
                .hasToString("2026-09-10");
    }

    @Test
    void rejectsRawOverflowControlsFormatsAndUnpairedSurrogates() throws Exception {
        var policy = policy("2026-09-09T00:00:00Z");
        for (String value :
                new String[] {"a".repeat(51), "bad\\u000a", "bad\\u200b", "bad\\ud800"}) {
            var node = mapper.readTree(body("2020-01-01", ",\"name\":\"" + value + "\""));
            assertThatThrownBy(() -> policy.validate(node))
                    .isInstanceOf(InputValidationException.class);
        }
        var normalized = policy.validate(mapper.readTree(body("2020-01-01", ",\"name\":\" 가 \"")));
        assertThat(normalized.details().name()).isEqualTo("가");
    }

    @Test
    void validatesStrictDateAndTimeWithoutCalendarRollover() throws Exception {
        var policy = policy("2026-09-09T00:00:00Z");
        for (String date : new String[] {"2025-02-29", "2020-1-01", "0000-01-01"}) {
            var node = mapper.readTree(body(date, ""));
            assertThatThrownBy(() -> policy.validate(node))
                    .isInstanceOf(InputValidationException.class);
        }
        for (String time : new String[] {"24:00:00", "12:00", "12:00:00.1"}) {
            var node = mapper.readTree(body("2020-01-01", ",\"eventTime\":\"" + time + "\""));
            assertThatThrownBy(() -> policy.validate(node))
                    .isInstanceOf(InputValidationException.class);
        }
    }

    @Test
    void hashesSemanticTextAndDecimalEqualityButPreservesPhotoOrder() throws Exception {
        var policy = policy("2026-09-09T00:00:00Z");
        var first = policy.validate(mapper.readTree(body("2020-01-01", ",\"name\":\" 가 \"")));
        var second = policy.validate(mapper.readTree(body("2020-01-01", ",\"name\":\"가\"")));
        var a =
                new com.meonggo.backend.photo.image.NormalizedPhoto(
                        new byte[] {1}, 512, 512, "a".repeat(64));
        var b =
                new com.meonggo.backend.photo.image.NormalizedPhoto(
                        new byte[] {2}, 512, 512, "b".repeat(64));
        assertThat(PostRequestHash.calculate(first, java.util.List.of(a, b)))
                .isEqualTo(PostRequestHash.calculate(second, java.util.List.of(a, b)))
                .isNotEqualTo(PostRequestHash.calculate(second, java.util.List.of(b, a)));
    }

    @Test
    void keepsHalfUpAtCoordinatePrecisionBoundary() throws Exception {
        var policy = policy("2026-09-09T00:00:00Z");
        for (String[] example :
                new String[][] {
                    {"0.000000499999", "0.000000"}, {"0.0000005", "0.000001"},
                    {"-0.000000499999", "0.000000"}, {"-0.0000005", "-0.000001"}
                }) {
            String body =
                    body("2020-01-01", "")
                            .replace(
                                    "\"exactLocationVisible\":false",
                                    "\"exactLocationVisible\":false,\"latitude\":"
                                            + example[0]
                                            + ",\"longitude\":0");
            assertThat(policy.validate(mapper.readTree(body)).eventLocation().latitude())
                    .isEqualByComparingTo(example[1]);
        }
    }

    /**
     * 안드로이드 앱이 실제로 보내는 payload 다. 앱이 동물 정보를 details 로 감싸고 위치에 publicLocation 을 얹어 보내는 동안 등록이 400 으로
     * 전부 막혔다. 컴파일로는 드러나지 않는 경계라 양쪽에서 같은 모양을 고정한다 (android CreatePostRequestSerializationTest).
     */
    @Test
    void acceptsThePayloadTheAndroidAppSends() throws Exception {
        var policy = policy("2026-09-09T00:00:00Z");
        String lost =
                """
                {"clientRequestId":"9cb37af4-5b75-4f72-9d58-257ee88e95f3","type":"LOST",
                "name":"망고","species":"DOG","breedName":"푸들","sex":"FEMALE","color":"갈색",
                "eventDate":"2020-01-01","eventTime":"17:30:00",
                "eventLocation":{"regionCode":"11680","exactLocation":"역삼역 3번 출구",
                "exactLocationVisible":false},"featureText":"빨간 목줄을 착용했습니다."}
                """;

        var request = policy.validate(mapper.readTree(lost));

        assertThat(request.details().name()).isEqualTo("망고");
        assertThat(request.details().eventTime()).hasToString("17:30");
        assertThat(request.eventLocation().exactLocation()).isEqualTo("역삼역 3번 출구");
        assertThat(request.currentLocation()).isNull();
    }

    /** 등록이 막혀 있던 두 모양이다. 되돌아오면 여기서 먼저 걸린다. */
    @Test
    void rejectsNestedDetailsAndClientSuppliedPublicLocation() throws Exception {
        var policy = policy("2026-09-09T00:00:00Z");
        String nested =
                """
                {"clientRequestId":"9cb37af4-5b75-4f72-9d58-257ee88e95f3","type":"LOST",
                "details":{"species":"DOG","sex":"UNKNOWN","eventDate":"2020-01-01"},
                "eventLocation":{"regionCode":"11680","exactLocationVisible":false}}
                """;
        String withPublicLocation =
                body("2020-01-01", "")
                        .replace(
                                "\"regionCode\":\"11680\"",
                                "\"regionCode\":\"11680\",\"publicLocation\":\"서울특별시 마포구\"");

        for (String payload : new String[] {nested, withPublicLocation}) {
            var node = mapper.readTree(payload);
            assertThatThrownBy(() -> policy.validate(node))
                    .isInstanceOf(InputValidationException.class);
        }
    }

    @Test
    void acceptsTheShelteringPayloadTheAndroidAppSends() throws Exception {
        var policy = policy("2026-09-09T00:00:00Z");
        String sheltering =
                """
                {"clientRequestId":"9cb37af4-5b75-4f72-9d58-257ee88e95f3","type":"SHELTERING",
                "species":"CAT","sex":"UNKNOWN","eventDate":"2020-01-01",
                "eventLocation":{"regionCode":"11680","exactLocationVisible":false},
                "currentLocation":{"regionCode":"11680","exactLocation":"자택",
                "exactLocationVisible":true,"disclosurePolicyVersion":"exact-location-v1"}}
                """;

        var request = policy.validate(mapper.readTree(sheltering));

        assertThat(request.currentLocation().exactLocation()).isEqualTo("자택");
        assertThat(request.currentLocation().exactLocationVisible()).isTrue();
    }

    private PostInputPolicy policy(String now) throws Exception {
        byte[] csv =
                "version,regionCode,emdCode,publicLocation,active\ntest,11680,,테스트 지역,true\n"
                        .getBytes(StandardCharsets.UTF_8);
        var catalog =
                new RegionCodeCatalog(
                        csv,
                        "test",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv)));
        return new PostInputPolicy(catalog, Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private String body(String date, String extra) {
        return """
            {"clientRequestId":"00000000-0000-0000-0000-000000000001","type":"LOST",
            "species":"DOG","sex":"UNKNOWN","eventDate":"%s",
            "eventLocation":{"regionCode":"11680","exactLocationVisible":false}%s}
            """
                .formatted(date, extra);
    }
}
