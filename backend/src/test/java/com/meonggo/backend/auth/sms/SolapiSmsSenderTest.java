package com.meonggo.backend.auth.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meonggo.backend.auth.exception.RetryableAuthException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SolapiSmsSenderTest {
    private HttpServer server;
    private SolapiSmsSender sender;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> reply = new AtomicReference<>("{}");
    private final AtomicInteger responseCode = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/send",
                exchange -> {
                    calls.incrementAndGet();
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] response = reply.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(responseCode.get(), response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        sender =
                new SolapiSmsSender(
                        JsonMapper.builder().build(),
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/send"),
                        "test-api-key",
                        "test-api-secret",
                        "0212345678");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsDomesticNumberAndAcceptsOnlyExplicitRegistration() {
        reply.set(
                "{\"groupInfo\":{\"count\":{\"registeredSuccess\":1,\"registeredFailed\":0}},\"failedMessageList\":[]}");
        sender.send("+821012345678", "123456");
        assertThat(body.get()).contains("01012345678", "123456", "messages");
        assertThat(auth.get())
                .startsWith("HMAC-SHA256 apiKey=test-api-key, date=")
                .contains(", salt=", ", signature=")
                .doesNotContain("test-api-secret");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void emptySuccessAndProviderFailureAreSafeAndNeverRetried() {
        assertThatThrownBy(() -> sender.send("+821012345678", "123456"))
                .isInstanceOf(RetryableAuthException.class)
                .hasNoCause()
                .hasMessageNotContaining("01012345678")
                .hasMessageNotContaining("123456")
                .hasMessageNotContaining("test-api-secret");
        responseCode.set(503);
        reply.set("provider-internal-sensitive-message");
        assertThatThrownBy(() -> sender.send("+821012345678", "123456"))
                .isInstanceOf(RetryableAuthException.class)
                .hasNoCause()
                .hasMessageNotContaining("provider-internal-sensitive-message");
        assertThat(calls.get()).isEqualTo(2);
    }
}
