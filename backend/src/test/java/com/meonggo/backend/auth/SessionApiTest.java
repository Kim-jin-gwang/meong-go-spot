package com.meonggo.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.security.AuthPrincipal;
import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.service.SessionService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SessionApiTest.Probe.class)
@ExtendWith(OutputCaptureExtension.class)
class SessionApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PasswordWork passwords;
    @Autowired private SessionService sessions;
    @Autowired private ObjectMapper mapper;
    private static final String PASSWORD = "LocalSessionPassword206!";
    private static String passwordHash;
    private long memberId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", RedisTestServer::url);
    }

    @BeforeEach
    void setup() {
        if (passwordHash == null) {
            try (var permit = passwords.acquire()) {
                passwordHash = permit.encode(PASSWORD);
            }
        }
        jdbc.update("delete from member where login_id = 'session-api-owner'");
        memberId =
                jdbc.queryForObject(
                        """
                insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
                  phone_ciphertext,phone_lookup_hash,phone_verified_at,
                  privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                values ('session-api-owner',?,'보호자','ACTIVE',now(),now(),
                  'test-envelope',repeat('8',64),now(),true, 'privacy-collection-v1',now()) returning id
                """,
                        Long.class,
                        passwordHash);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void loginRefreshReplayImmediatelyInvalidatesAccessToken(CapturedOutput output)
            throws Exception {
        var login = login("SESSION-API-OWNER", PASSWORD, 200);
        assertThat(login.path("data").path("member").path("memberId").asLong()).isEqualTo(memberId);
        String access = login.path("data").path("accessToken").asString();
        String refresh = login.path("data").path("refreshToken").asString();
        mvc.perform(get("/api/v1/session-test/probe").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(memberId));
        var renewed =
                mvc.perform(
                                post("/api/v1/auth/tokens/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of("refreshToken", refresh))))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("Pragma", "no-cache"))
                        .andExpect(jsonPath("$.data.member").doesNotExist())
                        .andReturn();
        assertThat(
                        mapper.readTree(renewed.getResponse().getContentAsString())
                                .path("data")
                                .path("refreshTokenExpiresAt"))
                .isEqualTo(login.path("data").path("refreshTokenExpiresAt"));
        mvc.perform(
                        post("/api/v1/auth/tokens/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-003"));
        mvc.perform(get("/api/v1/session-test/probe").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-003"));
        assertThat(output.getAll()).doesNotContain(access, refresh, PASSWORD);
    }

    @Test
    void tokensInQueryOrCookieAndDuplicateHeadersCannotAuthenticate() throws Exception {
        var token = sessions.create(memberId);
        mvc.perform(get("/api/v1/session-test/probe").param("access_token", token.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
        mvc.perform(
                        get("/api/v1/session-test/probe")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "accessToken", token.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
        mvc.perform(
                        get("/api/v1/session-test/probe")
                                .header(
                                        "Authorization",
                                        "Bearer " + token.accessToken(),
                                        "Bearer " + token.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
    }

    @Test
    void publicAllowlistIsLimitedToDocumentedMethodsAndResources() throws Exception {
        mvc.perform(get("/api/v1/posts").param("type", "LOST")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/data-sources/shelter-animals/daily-summary"))
                .andExpect(status().isOk());
        // 상세는 공개다 — 없는 게시물이라 404지만 401이 아니면 익명 접근이 허용된 것이다.
        mvc.perform(get("/api/v1/posts/1")).andExpect(status().isNotFound());
        // 상세 아래 하위 자원까지 공개 패턴이 삼키면 안 된다.
        mvc.perform(get("/api/v1/posts/1/candidates")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/posts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
    }

    @Test
    void logoutRetriesAreEmpty204AndDoNotRevokeAnotherDevice() throws Exception {
        var first = sessions.create(memberId);
        var second = sessions.create(memberId);
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .header("Authorization", "Bearer " + first.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("refreshToken", second.refreshToken()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-003"));
        for (int attempt = 0; attempt < 2; attempt++) {
            var result =
                    mvc.perform(
                                    post("/api/v1/auth/logout")
                                            .header(
                                                    "Authorization",
                                                    "Bearer " + first.accessToken())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    mapper.writeValueAsString(
                                                            Map.of(
                                                                    "refreshToken",
                                                                    first.refreshToken()))))
                            .andExpect(status().isNoContent())
                            .andReturn();
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }
        mvc.perform(
                        get("/api/v1/session-test/probe")
                                .header("Authorization", "Bearer " + second.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void invalidAndMissingBearerTokensUseAuthenticationError() throws Exception {
        mvc.perform(get("/api/v1/session-test/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
        for (String value : new String[] {"Basic ignored", "Bearer malformed", "Bearer "}) {
            var result =
                    mvc.perform(get("/api/v1/session-test/probe").header("Authorization", value))
                            .andExpect(status().isUnauthorized())
                            .andExpect(jsonPath("$.code").value("AUTH-002"))
                            .andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(value);
        }
    }

    @Test
    void fifthFailureBlocksCorrectPasswordWithRetryAfter() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++)
            login("session-api-owner", "incorrect-password", 401);
        var blocked = login("session-api-owner", "incorrect-password", 429);
        assertThat(blocked.path("code").asString()).isEqualTo("AUTH-004");
        login("session-api-owner", PASSWORD, 429);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_session where member_id=?",
                                Long.class,
                                memberId))
                .isZero();
    }

    @Test
    void invalidPresentPasswordsAndUnknownMembersHaveSameSafeResponse() throws Exception {
        var bad = login("session-api-owner", "", 401);
        var absent = login("session-api-unknown", PASSWORD, 401);
        assertThat(bad).isEqualTo(absent);
        assertThat(bad.path("code").asString()).isEqualTo("AUTH-001");
    }

    @Test
    void withdrawnStatusOnlyAppearsAfterCorrectPassword() throws Exception {
        jdbc.update("update member set status='WITHDRAWN',deleted_at=now() where id=?", memberId);
        assertThat(login("session-api-owner", "wrong-password-value", 401).path("code").asString())
                .isEqualTo("AUTH-001");
        assertThat(login("session-api-owner", PASSWORD, 403).path("code").asString())
                .isEqualTo("AUTH-005");
    }

    private JsonNode login(String id, String password, int expected) throws Exception {
        var action =
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of(
                                                                "loginId",
                                                                id,
                                                                "password",
                                                                password))))
                        .andExpect(status().is(expected));
        if (expected == 200)
            action.andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Pragma", "no-cache"));
        if (expected == 429) action.andExpect(header().exists("Retry-After"));
        return mapper.readTree(action.andReturn().getResponse().getContentAsString());
    }

    @RestController
    static class Probe {
        @GetMapping("/api/v1/session-test/probe")
        AuthPrincipal probe(@AuthenticationPrincipal AuthPrincipal principal) {
            return principal;
        }
    }

    @Test
    void missingLoginFieldsAreValidationErrorsOnPublicLoginRoute() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
    }
}
