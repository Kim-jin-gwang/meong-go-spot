package com.meonggo.backend.photo.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class PhotoReplacementGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {"1", "01", "+1"})
    void rejectsEncodedReplacementBeforeBodyParsingForPositiveIdAliases(String postId)
            throws Exception {
        var request = replacement(postId, 100);
        request.addHeader("Content-Encoding", "gzip");
        var response = new MockHttpServletResponse();
        var parsed = new AtomicInteger();

        new PhotoRequestFilter(new JsonMapper())
                .doFilter(request, response, (req, res) -> parsed.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("COMMON-001");
        assertThat(parsed.get()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"-1, 200, 1", "52428800, 200, 1", "52428801, 413, 0"})
    void enforcesKnownBodyLimitForLeadingZeroIdBeforeParsing(
            long length, int status, int parsingCount) throws Exception {
        var response = new MockHttpServletResponse();
        var parsed = new AtomicInteger();

        new PhotoRequestFilter(new JsonMapper())
                .doFilter(
                        replacement("01", length),
                        response,
                        (req, res) -> parsed.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(parsed.get()).isEqualTo(parsingCount);
        if (status == 413) assertThat(response.getContentAsString()).contains("PHOTO-004");
    }

    @ParameterizedTest
    @ValueSource(strings = {"01", "+1"})
    void positiveIdAliasesShareUploadCapacityAndReleaseItAfterCompletion(String postId)
            throws Exception {
        var filter = new PhotoUploadCapacityFilter(new JsonMapper(), 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var parsed = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first =
                    executor.submit(
                            () -> {
                                filter.doFilter(
                                        replacement(postId, 100),
                                        new MockHttpServletResponse(),
                                        (req, res) -> {
                                            entered.countDown();
                                            try {
                                                if (!release.await(10, TimeUnit.SECONDS))
                                                    throw new AssertionError("upload not released");
                                            } catch (InterruptedException ex) {
                                                Thread.currentThread().interrupt();
                                                throw new AssertionError(ex);
                                            }
                                        });
                                return null;
                            });
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                for (var request :
                        new MockHttpServletRequest[] {
                            replacement(postId, 100),
                            new MockHttpServletRequest("POST", "/api/v1/posts")
                        }) {
                    var response = new MockHttpServletResponse();
                    filter.doFilter(request, response, (req, res) -> parsed.incrementAndGet());
                    assertThat(response.getStatus()).isEqualTo(503);
                    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
                    assertThat(response.getContentAsString()).contains("PHOTO-008");
                }
                assertThat(parsed.get()).isZero();
            } finally {
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
            }
        }

        var response = new MockHttpServletResponse();
        filter.doFilter(replacement(postId, 100), response, (req, res) -> parsed.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(parsed.get()).isEqualTo(1);
    }

    private MockHttpServletRequest replacement(String postId, long length) {
        String path = "/api/v1/posts/" + postId + "/photos";
        var request =
                new MockHttpServletRequest("PUT", path) {
                    @Override
                    public long getContentLengthLong() {
                        return length;
                    }
                };
        request.setServletPath(path);
        return request;
    }
}
