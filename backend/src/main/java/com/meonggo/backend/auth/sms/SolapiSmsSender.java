package com.meonggo.backend.auth.sms;

import com.meonggo.backend.auth.exception.PhoneErrorCode;
import com.meonggo.backend.auth.exception.RetryableAuthException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** SOLAPI의 접수 결과만 판정하며 응답 원문·수신 번호·인증 헤더는 예외에 포함하지 않는다. */
public class SolapiSmsSender implements SmsSender {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String apiSecret;
    private final String senderNumber;

    public SolapiSmsSender(
            ObjectMapper mapper,
            URI endpoint,
            String apiKey,
            String apiSecret,
            String senderNumber) {
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.senderNumber = senderNumber;
    }

    @Override
    public void send(String e164PhoneNumber, String code) {
        try {
            String payload =
                    mapper.writeValueAsString(
                            Map.of(
                                    "messages",
                                    List.of(
                                            Map.of(
                                                    "to",
                                                    "0" + e164PhoneNumber.substring(3),
                                                    "from",
                                                    senderNumber,
                                                    "type",
                                                    "SMS",
                                                    "text",
                                                    "[멍고반점] 인증번호 [" + code + "]를 입력해 주세요."))));
            HttpRequest request =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(8))
                            .header("Content-Type", "application/json")
                            .header("Authorization", authorization())
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            payload, StandardCharsets.UTF_8))
                            .build();
            // 타임아웃·통신 오류에도 애플리케이션 재시도는 하지 않는다.
            HttpResponse<String> response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw unavailable();
            }
            JsonNode result = mapper.readTree(response.body());
            JsonNode count = result.path("groupInfo").path("count");
            if (count.path("registeredSuccess").asInt(-1) != 1
                    || count.path("registeredFailed").asInt(-1) != 0
                    || !result.path("failedMessageList").isArray()
                    || !result.path("failedMessageList").isEmpty()) {
                throw unavailable();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception ex) {
            throw unavailable();
        }
    }

    private String authorization() throws GeneralSecurityException {
        String date = Instant.now().toString();
        String salt = UUID.randomUUID().toString().replace("-", "");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature =
                HexFormat.of()
                        .formatHex(mac.doFinal((date + salt).getBytes(StandardCharsets.UTF_8)));
        return "HMAC-SHA256 apiKey="
                + apiKey
                + ", date="
                + date
                + ", salt="
                + salt
                + ", signature="
                + signature;
    }

    private static RetryableAuthException unavailable() {
        return new RetryableAuthException(PhoneErrorCode.SMS_UNAVAILABLE, 60);
    }
}
