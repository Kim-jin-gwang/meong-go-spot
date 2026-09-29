package com.meonggo.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.security.PhoneProtection;
import com.meonggo.backend.auth.sms.SmsSender;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class SignupApiTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PhoneProtection protection;
    @MockitoBean private SmsSender sms;
    // 테스트 프로필에만 존재하는 SMS inspector. 운영 Fake는 원문을 저장하지 않는다.
    private final Map<String, String> deliveredCodes = new ConcurrentHashMap<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", RedisTestServer::url);
    }

    @BeforeEach
    void setup() {
        // 전용 테스트 DB를 사용한다. 다른 fixture 회원은 제거하지 않는다.
        jdbc.update("delete from member where login_id like 'signup-test-%'");
        deliveredCodes.clear();
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        doAnswer(
                        invocation -> {
                            deliveredCodes.put(
                                    invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(sms)
                .send(anyString(), anyString());
    }

    @Test
    void signupValidatesAnonymousRequest() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        mockMvc.perform(
                        post("/api/v1/auth/phone-verifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("phoneNumber", "010-1234-5678"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        mockMvc.perform(get("/api/v1/auth/signup")).andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completeFlowStoresOnlyProtectedPhoneAndPasswordAndConsumesProof(CapturedOutput output)
            throws Exception {
        String phone = "01070000001";
        String token = verified(phone);
        Map<String, Object> request = signup("Signup-Test-Owner", phone, token);
        MvcResult result = performSignup(request, 201);
        assertThat(result.getResponse().getContentAsString())
                .contains("signup-test-owner", "망고보호자")
                .doesNotContain(phone, token, "password", "phoneCiphertext", "privacyCollection");
        Map<String, Object> row =
                jdbc.queryForMap("select * from member where login_id = ?", "signup-test-owner");
        assertThat(row.get("phone_ciphertext").toString())
                .startsWith("enc:v1:V1:")
                .doesNotContain(phone);
        assertThat(protection.decrypt(row.get("phone_ciphertext").toString()))
                .isEqualTo("+821070000001");
        assertThat(row.get("password_hash").toString())
                .startsWith("{argon2id-v1}$argon2id$v=19$m=65536,t=3,p=4$");
        assertThat(row.get("privacy_collection_policy_version")).isEqualTo("privacy-collection-v1");
        assertThat(row.get("privacy_collection_agreed")).isEqualTo(true);
        assertThat(row.get("phone_verified_at")).isNotNull();
        assertThat(row.get("privacy_collection_consented_at")).isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from auth_session where member_id = ?",
                                Long.class,
                                row.get("id")))
                .isZero();
        performSignup(request, 400);
        assertThat(output.getAll())
                .doesNotContain(
                        phone,
                        token,
                        request.get("password").toString(),
                        row.get("password_hash").toString(),
                        row.get("phone_lookup_hash").toString());
        Map<Object, Object> proofState =
                redis.opsForHash().entries("mgbj:auth:proof:" + token.split("\\.")[1]);
        assertThat(proofState).containsEntry("status", "CONSUMED");
        assertThat(proofState.toString()).doesNotContain(token, phone);
    }

    @Test
    void fakeSmsMasterPhoneSignsUpWithoutVerificationAndStillBlocksDuplicates(CapturedOutput output)
            throws Exception {
        String phone = "011-0000-0000";
        Map<String, Object> first = signup("signup-test-master", phone, "unused");
        first.remove("phoneVerificationToken");

        MvcResult result = performSignup(first, 201);

        assertThat(result.getResponse().getContentAsString()).doesNotContain(phone);
        Map<String, Object> row =
                jdbc.queryForMap("select * from member where login_id=?", "signup-test-master");
        assertThat(protection.decrypt(row.get("phone_ciphertext").toString()))
                .isEqualTo("+821100000000");
        assertThat(row.get("phone_verified_at")).isNotNull();
        assertThat(redis.keys("mgbj:auth:*")).isEmpty();

        Map<String, Object> duplicate = signup("signup-test-master-duplicate", phone, "unused");
        duplicate.remove("phoneVerificationToken");
        assertThat(performSignup(duplicate, 409).getResponse().getContentAsString())
                .contains("PHONE-003")
                .doesNotContain(phone);
        assertThat(output).doesNotContain(phone);
    }

    @Test
    void ordinaryPhoneStillRequiresVerificationProof() throws Exception {
        Map<String, Object> request = signup("signup-test-proof-required", "01070000011", "unused");
        request.remove("phoneVerificationToken");

        assertThat(performSignup(request, 400).getResponse().getContentAsString())
                .contains("COMMON-001", "phoneVerificationToken");
    }

    @Test
    void invalidConsentAndPhoneBindingDoNotConsumeProof() throws Exception {
        String phone = "01070000002";
        String token = verified(phone);
        Map<String, Object> request = signup("signup-test-consent", phone, token);
        request.remove("privacyCollectionPolicyVersion");
        assertThat(performSignup(request, 409).getResponse().getContentAsString())
                .contains("MEMBER-002");
        request.put("privacyCollectionPolicyVersion", "privacy-collection-v1");
        request.put("phoneNumber", "01070000003");
        assertThat(performSignup(request, 400).getResponse().getContentAsString())
                .contains("PHONE-002");
        request.put("phoneNumber", phone);
        performSignup(request, 201);
    }

    @Test
    void duplicateCanonicalLoginRestoresProofForAnotherLogin() throws Exception {
        performSignup(signup("signup-test-duplicate", "01070000004", verified("01070000004")), 201);
        String token = verified("01070000005");
        Map<String, Object> request = signup("SIGNUP-TEST-DUPLICATE", "01070000005", token);
        assertThat(performSignup(request, 409).getResponse().getContentAsString())
                .contains("MEMBER-001", "이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요.");
        request.put("loginId", "signup-test-recovered");
        performSignup(request, 201);
    }

    @Test
    void sameProofConcurrentRequestsCreateOnlyOneMember() throws Exception {
        String phone = "01070000006";
        Map<String, Object> request = signup("signup-test-race", phone, verified(phone));
        String json = mapper.writeValueAsString(request);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> action =
                    () -> {
                        start.await();
                        return mockMvc.perform(
                                        post("/api/v1/auth/signup")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(201, 400);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from member where login_id = 'signup-test-race'",
                                Long.class))
                .isEqualTo(1);
    }

    @Test
    void distinctProofsForSamePhoneCannotCreateTwoAccounts() throws Exception {
        String phone = "01070000009";
        String firstToken = verified(phone);
        redis.delete("mgbj:auth:cooldown:" + protection.lookupHash(protection.normalize(phone)));
        String secondToken = verified(phone);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () -> {
                                start.await();
                                return performSignupUnchecked(
                                        signup("signup-test-phone-race-a", phone, firstToken));
                            });
            var second =
                    executor.submit(
                            () -> {
                                start.await();
                                return performSignupUnchecked(
                                        signup("signup-test-phone-race-b", phone, secondToken));
                            });
            start.countDown();
            assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from member where phone_lookup_hash = ?",
                                Long.class,
                                protection.lookupHash(protection.normalize(phone))))
                .isEqualTo(1);
    }

    @Test
    void missingNullOrFalseConsentReturnsFieldErrors() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            Map<String, Object> request =
                    signup("signup-test-no-consent", "01070000010", "test-proof");
            if (variant == 0) {
                request.remove("privacyCollectionAgreed");
            }
            if (variant == 1) {
                request.put("privacyCollectionAgreed", null);
            }
            if (variant == 2) {
                request.put("privacyCollectionAgreed", false);
            }
            String response = performSignup(request, 400).getResponse().getContentAsString();
            assertThat(response).contains("COMMON-001", "fieldErrors", "privacyCollectionAgreed");
        }
    }

    @Test
    void phoneVerificationRejectsMissingOrFalseConsentBeforeSendingSms() throws Exception {
        String phone = "01070000012";
        for (int variant = 0; variant < 3; variant++) {
            Map<String, Object> request = new HashMap<>(phoneConsent(phone));
            if (variant == 0) request.remove("privacyCollectionAgreed");
            if (variant == 1) request.put("privacyCollectionAgreed", null);
            if (variant == 2) request.put("privacyCollectionAgreed", false);
            String response =
                    mockMvc.perform(
                                    post("/api/v1/auth/phone-verifications")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(mapper.writeValueAsString(request)))
                            .andExpect(status().isBadRequest())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(response).contains("COMMON-001", "privacyCollectionAgreed");
        }
        Map<String, Object> stale = new HashMap<>(phoneConsent(phone));
        stale.put("privacyCollectionPolicyVersion", "outdated");
        mockMvc.perform(
                        post("/api/v1/auth/phone-verifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER-002"));
        assertThat(deliveredCodes).isEmpty();
        send(phone);
    }

    @Test
    void phoneConfirmationRejectsStaleConsentWithoutConsumingOtp() throws Exception {
        String phone = "01070000013";
        send(phone);
        Map<String, Object> stale =
                new HashMap<>(
                        phoneConfirmation(phone, deliveredCodes.get(protection.normalize(phone))));
        stale.put("privacyCollectionPolicyVersion", "outdated");
        mockMvc.perform(
                        post("/api/v1/auth/phone-verifications/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBER-002"));
        confirm(phone, deliveredCodes.get(protection.normalize(phone)), 200);
    }

    private int performSignupUnchecked(Map<String, Object> request) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void resendIsLimitedAndFifthWrongCodeDestroysOtp() throws Exception {
        String phone = "01070000007";
        send(phone);
        mockMvc.perform(
                        post("/api/v1/auth/phone-verifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(phoneConsent(phone))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        String actual = deliveredCodes.get(protection.normalize(phone));
        String wrong = "000000".equals(actual) ? "111111" : "000000";
        for (int i = 0; i < 5; i++) {
            confirm(phone, wrong, 400);
        }
        confirm(phone, actual, 400);
    }

    @Test
    void withdrawnPhonePendingErasureStillBlocksSignup() throws Exception {
        String phone = "01070000008";
        performSignup(signup("signup-test-withdrawn", phone, verified(phone)), 201);
        jdbc.update(
                "update member set status='WITHDRAWN', deleted_at=now() where login_id='signup-test-withdrawn'");
        // 테스트 clock 대기 없이 전용 인증 상태만 초기화한다.
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        send(phone);
        MvcResult result = confirm(phone, deliveredCodes.get(protection.normalize(phone)), 409);
        assertThat(result.getResponse().getContentAsString())
                .contains("PHONE-003")
                .doesNotContain("signup-test-withdrawn", "WITHDRAWN");
    }

    private Map<String, Object> signup(String login, String phone, String token) {
        Map<String, Object> request = new HashMap<>();
        request.put("loginId", login);
        request.put("password", "멍고반점-안전한비밀번호!2026");
        request.put("nickname", "  망고보호자  ");
        request.put("phoneNumber", phone);
        request.put("phoneVerificationToken", token);
        request.put("privacyCollectionAgreed", true);
        request.put("privacyCollectionPolicyVersion", "privacy-collection-v1");
        return request;
    }

    private MvcResult performSignup(Map<String, Object> request, int expectedStatus)
            throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private void send(String phone) throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/phone-verifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(phoneConsent(phone))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private MvcResult confirm(String phone, String code, int expectedStatus) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/phone-verifications/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(phoneConfirmation(phone, code))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private Map<String, Object> phoneConsent(String phone) {
        return Map.of(
                "phoneNumber",
                phone,
                "privacyCollectionAgreed",
                true,
                "privacyCollectionPolicyVersion",
                "privacy-collection-v1");
    }

    private Map<String, Object> phoneConfirmation(String phone, String code) {
        Map<String, Object> request = new HashMap<>(phoneConsent(phone));
        request.put("verificationCode", code);
        return request;
    }

    private String verified(String phone) throws Exception {
        send(phone);
        MvcResult result = confirm(phone, deliveredCodes.get(protection.normalize(phone)), 200);
        return mapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("phoneVerificationToken")
                .asString();
    }
}
