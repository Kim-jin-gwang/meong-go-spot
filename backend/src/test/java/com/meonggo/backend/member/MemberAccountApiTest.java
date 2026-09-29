package com.meonggo.backend.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.RedisTestServer;
import com.meonggo.backend.auth.dto.TokenResponse;
import com.meonggo.backend.auth.security.PasswordWork;
import com.meonggo.backend.auth.service.SessionService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** A5 탈퇴 · A6 프로필 · A7 닉네임 · A8 비밀번호 변경 (docs/api-spec.md §5). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberAccountApiTest {
    private static final String LOGIN_ID = "account-api-owner";
    private static final String PASSWORD = "LocalAccountPassword206!";
    private static final String NEW_PASSWORD = "RotatedAccountPassword206!";
    private static String passwordHash;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PasswordWork passwords;
    @Autowired private SessionService sessions;
    @Autowired private ObjectMapper mapper;
    private long memberId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", RedisTestServer::url);
    }

    // phone_lookup_hash 는 ACTIVE 회원에 대해 부분 유일 인덱스다 — 다른 테스트 클래스(SessionServiceTest 는 '9')와 겹치면
    // 전체 스위트에서만 DuplicateKey 로 터진다. 클래스마다 다른 문자를 쓴다.
    @BeforeEach
    void setup() {
        if (passwordHash == null) {
            try (var permit = passwords.acquire()) {
                passwordHash = permit.encode(PASSWORD);
            }
        }
        jdbc.update(
                "delete from animal_case where id in (select animal_case_id from user_post where member_id in (select id from member where login_id = ?))",
                LOGIN_ID);
        jdbc.update("delete from member where login_id = ?", LOGIN_ID);
        memberId =
                jdbc.queryForObject(
                        """
                insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
                  phone_ciphertext,phone_lookup_hash,phone_verified_at,
                  privacy_collection_agreed, privacy_collection_policy_version,privacy_collection_consented_at)
                values (?,?,'보호자','ACTIVE',now(),now(),
                  'test-envelope',repeat('7',64),now(),true, 'privacy-collection-v1',now()) returning id
                """,
                        Long.class,
                        LOGIN_ID,
                        passwordHash);
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void profileRequiresAuthenticationAndNeverExposesPhoneOrLoginId() throws Exception {
        mvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-002"));
        var session = sessions.create(memberId);
        mvc.perform(get("/api/v1/members/me").header("Authorization", bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberId").value(memberId))
                .andExpect(jsonPath("$.data.nickname").value("보호자"))
                .andExpect(jsonPath("$.data.loginId").doesNotExist())
                .andExpect(jsonPath("$.data.phone").doesNotExist());
    }

    @Test
    void nicknameChangeNormalizesAndValidatesLikeSignup() throws Exception {
        var session = sessions.create(memberId);
        json(patch("/api/v1/members/me"), session, Map.of("nickname", "  망고보호자 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("망고보호자"));
        assertThat(
                        jdbc.queryForObject(
                                "select nickname from member where id=?", String.class, memberId))
                .isEqualTo("망고보호자");
        json(patch("/api/v1/members/me"), session, Map.of("nickname", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("nickname"));
        json(patch("/api/v1/members/me"), session, Map.of("nickname", "a".repeat(31)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordChangeReauthenticatesAndRevokesOnlyOtherSessions() throws Exception {
        var current = sessions.create(memberId);
        var other = sessions.create(memberId);

        json(
                        put("/api/v1/members/me/password"),
                        current,
                        Map.of("currentPassword", "WrongPassword206!", "newPassword", NEW_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-001"));
        json(
                        put("/api/v1/members/me/password"),
                        current,
                        Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER-003"));
        json(
                        put("/api/v1/members/me/password"),
                        current,
                        Map.of("currentPassword", PASSWORD, "newPassword", "password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("newPassword"));
        assertThat(storedHash()).isEqualTo(passwordHash);

        json(
                        put("/api/v1/members/me/password"),
                        current,
                        Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))
                .andExpect(status().isNoContent());
        assertThat(storedHash()).isNotEqualTo(passwordHash).startsWith("{argon2id-v1}");

        // 바꾼 기기는 그대로, 다른 기기는 끊긴다.
        mvc.perform(get("/api/v1/members/me").header("Authorization", bearer(current)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/members/me").header("Authorization", bearer(other)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-003"));

        login(PASSWORD).andExpect(status().isUnauthorized());
        login(NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void withdrawalRevokesEverySessionAndDeletesOwnedPosts() throws Exception {
        long postId = ownedPost();
        var session = sessions.create(memberId);
        jdbc.update(
                """
                update auth_session
                set push_installation_id=?,push_platform='ANDROID',
                    push_token_ciphertext='encrypted-test-token',push_token_lookup_hash=?,
                    push_last_seen_at=now()
                where id=(select max(id) from auth_session where member_id=?)
                """,
                UUID.randomUUID(),
                "f".repeat(64),
                memberId);

        json(
                        post("/api/v1/members/me/withdrawal"),
                        session,
                        Map.of("currentPassword", "WrongPassword206!"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-001"));
        assertThat(memberStatus()).isEqualTo("ACTIVE");

        var result =
                json(
                                post("/api/v1/members/me/withdrawal"),
                                session,
                                Map.of("currentPassword", PASSWORD))
                        .andExpect(status().isNoContent())
                        .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();

        assertThat(memberStatus()).isEqualTo("WITHDRAWN");
        assertThat(
                        jdbc.queryForObject(
                                """
                                select count(*) from auth_session
                                where member_id=? and push_installation_id is not null
                                """,
                                Integer.class,
                                memberId))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select deleted_at is not null from member where id=?",
                                Boolean.class,
                                memberId))
                .isTrue();
        assertThat(
                        jdbc.queryForMap(
                                "select status,is_matchable,deleted_at from animal_case where id=?",
                                postId))
                .containsEntry("status", "DELETED")
                .containsEntry("is_matchable", false)
                .doesNotContainEntry("deleted_at", null);
        mvc.perform(get("/api/v1/members/me").header("Authorization", bearer(session)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH-003"));
        // 비밀번호가 맞아도 탈퇴 회원은 새 세션을 받지 못한다.
        login(PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH-005"));
    }

    private ResultActions json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            TokenResponse session,
            Map<String, String> body)
            throws Exception {
        return mvc.perform(
                request.header("Authorization", bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                mapper.writeValueAsString(
                                        Map.of("loginId", LOGIN_ID, "password", password))));
    }

    private static String bearer(TokenResponse session) {
        return "Bearer " + session.accessToken();
    }

    private String storedHash() {
        return jdbc.queryForObject(
                "select password_hash from member where id=?", String.class, memberId);
    }

    private String memberStatus() {
        return jdbc.queryForObject("select status from member where id=?", String.class, memberId);
    }

    private long ownedPost() {
        long postId =
                jdbc.queryForObject(
                        "insert into animal_case(case_type,source_type,status,listed_at,species,sex,event_date,created_at,updated_at) values('LOST','USER','ACTIVE',now(),'DOG','UNKNOWN','2026-09-01',now(),now()) returning id",
                        Long.class);
        jdbc.update(
                "insert into user_post(animal_case_id,member_id,client_request_id,request_hash) values(?,?,?,?)",
                postId,
                memberId,
                UUID.randomUUID(),
                "0".repeat(64));
        return postId;
    }
}
