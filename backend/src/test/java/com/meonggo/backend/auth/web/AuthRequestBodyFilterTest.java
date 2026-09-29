package com.meonggo.backend.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class AuthRequestBodyFilterTest {
    @Test
    void encodedRequestUriCannotBypassDecodedServletRouteLimit() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/%73ignup");
        request.setServletPath("/api/v1/auth/signup");
        request.setContent(new byte[8193]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger downstream = new AtomicInteger();
        new AuthRequestBodyFilter(JsonMapper.builder().build())
                .doFilter(request, response, (req, res) -> downstream.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(downstream.get()).isZero();
    }

    private final AuthRequestBodyFilter filter =
            new AuthRequestBodyFilter(JsonMapper.builder().build());

    @Test
    void enforcesActualStreamLimitEvenWithoutLengthHeader() throws Exception {
        AtomicInteger downstream = new AtomicInteger();
        for (int length : new int[] {8192, 8193}) {
            MockHttpServletRequest request =
                    new MockHttpServletRequest("POST", "/api/v1/auth/signup") {
                        @Override
                        public long getContentLengthLong() {
                            return -1;
                        }
                    };
            request.setContent(new byte[length]);
            request.addHeader("Transfer-Encoding", "chunked");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                        assertThat(req.getInputStream().readAllBytes()).hasSize(8192);
                        downstream.incrementAndGet();
                    });
            assertThat(response.getStatus()).isEqualTo(length == 8192 ? 200 : 400);
            if (length > 8192) {
                assertThat(response.getContentAsString()).contains("COMMON-001");
            }
        }
        assertThat(downstream.get()).isEqualTo(1);
    }

    @Test
    void rejectsCompressionBeforeReadingBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/signup");
        request.addHeader("Content-Encoding", "gzip");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger downstream = new AtomicInteger();
        filter.doFilter(request, response, (req, res) -> downstream.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(downstream.get()).isZero();
    }
}
