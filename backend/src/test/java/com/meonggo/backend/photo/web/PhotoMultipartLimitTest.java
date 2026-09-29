package com.meonggo.backend.photo.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PhotoMultipartLimitTest {
    @LocalServerPort private int port;
    @Autowired private SessionService sessions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private LoginService login;
    private String token;

    @BeforeEach
    void setup() {
        long member =
                jdbc.queryForObject(
                        """
                insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
                  phone_ciphertext,phone_lookup_hash,phone_verified_at,
                  privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                values(?,'fixture','작성자','ACTIVE',now(),now(),'test-envelope',?,now(),true, 'privacy-collection-v1',now()) returning id
                """,
                        Long.class,
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString().replace("-", "").repeat(2));
        token = sessions.create(member).accessToken();
    }

    @Test
    void realChunkedMultipartCountsBoundariesAndStopsAtTotalLimit() throws Exception {
        for (long total : new long[] {52428799, 52428800, 52428801}) {
            var response = send(total, 5);
            // 실제 P3에서 payload 누락까지 도달했다면 전체 전송량 검사를 통과한 것이다.
            assertThat(response.statusCode()).isEqualTo(total > 52428800 ? 413 : 400);
            if (total <= 52428800) assertThat(response.body()).contains("COMMON-001");
            if (total > 52428800)
                assertThat(response.body())
                        .contains("PHOTO-004")
                        .doesNotContain("Exception", "filename", "/tmp/");
        }
    }

    @Test
    void servletFileLimitUsesPhotoErrorContract() throws Exception {
        var response = send(10487000, 1);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("PHOTO-004");
    }

    @Test
    void replacementPutUsesTheSameStreamingLimits() throws Exception {
        for (long total : new long[] {52428800, 52428801}) {
            var response = send(total, 5, "PUT", "/api/v1/posts/1/photos");
            assertThat(response.statusCode()).isEqualTo(total > 52428800 ? 413 : 400);
            assertThat(response.body()).contains(total > 52428800 ? "PHOTO-004" : "COMMON-001");
        }
        assertThat(send(10487000, 1, "PUT", "/api/v1/posts/1/photos").body()).contains("PHOTO-004");
    }

    private HttpResponse<String> send(long total, int parts) throws Exception {
        return send(total, parts, "POST", "/api/v1/posts");
    }

    private HttpResponse<String> send(long total, int parts, String method, String path)
            throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request =
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "multipart/form-data; boundary=photo-boundary")
                            .method(
                                    method,
                                    HttpRequest.BodyPublishers.ofInputStream(
                                            () -> multipart(total, parts)))
                            .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private InputStream multipart(long total, int count) {
        byte[] header =
                ("--photo-boundary\r\nContent-Disposition: form-data; name=\"photos\"; filename=\"fixture.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "--photo-boundary--\r\n".getBytes(StandardCharsets.US_ASCII);
        long payload = total - (long) count * (header.length + 2) - tail.length;
        List<InputStream> pieces = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long length = payload / count + (i < payload % count ? 1 : 0);
            pieces.add(new ByteArrayInputStream(header));
            pieces.add(new Zeros(length));
            pieces.add(new ByteArrayInputStream(new byte[] {13, 10}));
        }
        pieces.add(new ByteArrayInputStream(tail));
        return new SequenceInputStream(Collections.enumeration(pieces));
    }

    private static class Zeros extends InputStream {
        private long remaining;

        Zeros(long remaining) {
            this.remaining = remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) return -1;
            remaining--;
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (remaining == 0) return -1;
            int size = (int) Math.min(remaining, length);
            java.util.Arrays.fill(bytes, offset, offset + size, (byte) 0);
            remaining -= size;
            return size;
        }
    }
}
