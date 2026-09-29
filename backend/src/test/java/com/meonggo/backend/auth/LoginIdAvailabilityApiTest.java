package com.meonggo.backend.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginIdAvailabilityApiTest {
    private static final String PREFIX = "availability-test-";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", RedisTestServer::url);
    }

    @BeforeEach
    void setup() {
        cleanUp();
        jdbc.update(
                """
                insert into member(
                    login_id,password_hash,nickname,status,created_at,updated_at,
                    phone_ciphertext,phone_lookup_hash,phone_verified_at,
                    privacy_collection_agreed,privacy_collection_policy_version,
                    privacy_collection_consented_at
                ) values(?,'fixture','기존 회원','ACTIVE',now(),now(),?,?,now(),true,?,now())
                """,
                PREFIX + "owner",
                "test-envelope",
                randomPhoneHash(),
                "privacy-collection-v1");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from member where login_id like ?", PREFIX + "%");
    }

    @Test
    void anonymousRequestReturnsAvailableForUnusedLoginId() throws Exception {
        mockMvc.perform(
                        get("/api/v1/auth/login-ids/availability")
                                .queryParam("loginId", PREFIX + "new-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("사용할 수 있는 아이디입니다."))
                .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    void canonicalCaseVariantReturnsTakenWithGuidance() throws Exception {
        mockMvc.perform(
                        get("/api/v1/auth/login-ids/availability")
                                .queryParam("loginId", "AVAILABILITY-TEST-OWNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요."))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    void missingOrInvalidLoginIdReturnsValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login-ids/availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-003"));

        mockMvc.perform(
                        get("/api/v1/auth/login-ids/availability")
                                .queryParam("loginId", "availability test owner"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("loginId"));
    }

    private String randomPhoneHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }
}
