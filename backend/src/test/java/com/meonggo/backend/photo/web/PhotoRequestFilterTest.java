package com.meonggo.backend.photo.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class PhotoRequestFilterTest {
    @Test
    void rejectsKnownOversizeAndCompressionBeforeBodyRead() throws Exception {
        var filter = new PhotoRequestFilter(new JsonMapper());
        for (String method : new String[] {"POST", "PUT"}) {
            var request =
                    new MockHttpServletRequest(
                            method,
                            method.equals("POST") ? "/api/v1/posts" : "/api/v1/posts/1/photos") {
                        @Override
                        public long getContentLengthLong() {
                            return 52428801;
                        }
                    };
            var response = new MockHttpServletResponse();
            var invoked = new AtomicInteger();
            filter.doFilter(request, response, (req, res) -> invoked.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getContentAsString()).contains("PHOTO-004");
            assertThat(invoked.get()).isZero();
        }
        var compressed = new MockHttpServletRequest("POST", "/api/v1/posts");
        compressed.addHeader("Content-Encoding", "gzip");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                compressed,
                response,
                (req, res) -> {
                    throw new AssertionError("compressed body parsed");
                });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void decodedPathAndInclusiveLimitAreEnforced() throws Exception {
        for (long length : new long[] {52428800, 52428801}) {
            var request =
                    new MockHttpServletRequest("POST", "/api/v1/%70osts") {
                        @Override
                        public long getContentLengthLong() {
                            return length;
                        }
                    };
            request.setServletPath("/api/v1/posts");
            var response = new MockHttpServletResponse();
            new PhotoRequestFilter(new JsonMapper()).doFilter(request, response, (req, res) -> {});
            assertThat(response.getStatus()).isEqualTo(length == 52428800 ? 200 : 413);
        }
    }
}
