package com.meonggo.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.security.PhoneProtection;
import com.meonggo.backend.auth.sms.SmsSender;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

/** A9 계정 찾기 — 아이디 확인·비밀번호 재설정·세션 폐기·번호 열거 방지·가입 증명과의 분리. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AccountRecoveryApiTest {
    private static final String PASSWORD = "멍고반점-안전한비밀번호!2026";
    private static final String NEW_PASSWORD = "새로운-비밀번호-멍고!2026";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PhoneProtection protection;
    @MockitoBean private SmsSender sms;
    private final Map<String, String> deliveredCodes = new ConcurrentHashMap<>();
    private int deliveries;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url", RedisTestServer::url);
    }

    @BeforeEach
    void setup() {
        jdbc.update("delete from member where login_id like 'recovery-test-%'");
        deliveredCodes.clear();
        deliveries = 0;
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        doAnswer(
                        invocation -> {
                            deliveries++;
                            deliveredCodes.put(
                                    invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(sms)
                .send(anyString(), anyString());
    }

    @Test
    void recoversLoginIdResetsPasswordAndRevokesEverySession(CapturedOutput output)
            throws Exception {
        String phone = "01071000001";
        String loginId = "recovery-test-owner";
        signup(loginId, phone);
        String refreshBefore = login(loginId, PASSWORD);

        // A9-1·A9-2: 번호 소유를 증명하면 아이디와 복구 증명을 받는다
        requestRecoveryCode(phone);
        String code = deliveredCodes.get(protection.normalize(phone));
        MvcResult confirmed = confirmRecovery(phone, code, 200);
        var data = mapper.readTree(confirmed.getResponse().getContentAsString()).path("data");
        assertThat(data.path("loginId").asString()).isEqualTo(loginId);
        String token = data.path("recoveryToken").asString();
        assertThat(token).startsWith("pv1.").hasSize(70);

        // A9-3: 비밀번호를 바꾸면 이전 세션은 전부 끊기고 새 비밀번호로만 로그인된다
        resetPassword(loginId, token, NEW_PASSWORD, 204);
        assertThat(sessionsAlive(loginId)).isZero();
        refresh(refreshBefore, 401);
        loginStatus(loginId, PASSWORD, 401);
        login(loginId, NEW_PASSWORD);

        // 복구 증명은 1회용
        resetPassword(loginId, token, "또다른-비밀번호-멍고!2026", 400);

        // 원문 코드·증명·번호·비밀번호는 로그에 없다
        assertThat(output.getOut() + output.getErr())
                .doesNotContain(code, token, phone, PASSWORD, NEW_PASSWORD);
    }

    @Test
    void unknownPhoneGetsTheSameAcceptedResponseWithoutAnSms() throws Exception {
        requestRecoveryCode("01071000002");
        assertThat(deliveries).isZero();
        // 코드가 없으니 확인은 실패하지만 "미가입" 이라고 말하지 않는다
        MvcResult result = confirmRecovery("01071000002", "123456", 400);
        assertThat(result.getResponse().getContentAsString())
                .contains("PHONE-002")
                .doesNotContain("가입", "회원 없음");
    }

    @Test
    void signupProofCannotResetAPasswordAndRecoveryProofIsBoundToTheLoginId() throws Exception {
        String phone = "01071000003";
        String other = "01071000004";
        signup("recovery-test-a", phone);
        signup("recovery-test-b", other);

        // 가입용 증명(pv1, 가입 키 공간)으로는 재설정 불가
        String signupProof = signupProof("01071000005");
        resetPassword("recovery-test-a", signupProof, NEW_PASSWORD, 400);

        // a 의 번호로 받은 복구 증명으로 b 의 비밀번호는 바꿀 수 없다
        requestRecoveryCode(phone);
        String token =
                mapper.readTree(
                                confirmRecovery(
                                                phone,
                                                deliveredCodes.get(protection.normalize(phone)),
                                                200)
                                        .getResponse()
                                        .getContentAsString())
                        .path("data")
                        .path("recoveryToken")
                        .asString();
        resetPassword("recovery-test-b", token, NEW_PASSWORD, 400);
        loginStatus("recovery-test-b", PASSWORD, 200);

        // 같은 증명으로 본인 것은 된다 — 위 실패가 증명을 소비하지 않았다
        resetPassword("recovery-test-a", token, NEW_PASSWORD, 204);
    }

    @Test
    void validatesTheNewPasswordLikeSignupAndRejectsTheUnchangedOne() throws Exception {
        String phone = "01071000006";
        signup("recovery-test-pw", phone);
        requestRecoveryCode(phone);
        String token =
                mapper.readTree(
                                confirmRecovery(
                                                phone,
                                                deliveredCodes.get(protection.normalize(phone)),
                                                200)
                                        .getResponse()
                                        .getContentAsString())
                        .path("data")
                        .path("recoveryToken")
                        .asString();

        MvcResult weak = resetPassword("recovery-test-pw", token, "short", 400);
        assertThat(weak.getResponse().getContentAsString())
                .contains("COMMON-001", "\"newPassword\"");
        MvcResult same = resetPassword("recovery-test-pw", token, PASSWORD, 409);
        assertThat(same.getResponse().getContentAsString()).contains("MEMBER-003");
        // 검증 실패는 증명을 소비하지 않는다
        resetPassword("recovery-test-pw", token, NEW_PASSWORD, 204);
    }

    @Test
    void recoveryRequestsAreRateLimitedPerPhone() throws Exception {
        String phone = "01071000007";
        signup("recovery-test-limit", phone);
        requestRecoveryCode(phone);
        mockMvc.perform(
                        post("/api/v1/auth/account-recovery/phone-verifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("phoneNumber", phone))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    // ── helpers ──

    private void signup(String loginId, String phone) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("loginId", loginId);
        request.put("password", PASSWORD);
        request.put("nickname", "복구테스트");
        request.put("phoneNumber", phone);
        request.put("phoneVerificationToken", signupProof(phone));
        request.put("privacyCollectionAgreed", true);
        request.put("privacyCollectionPolicyVersion", "privacy-collection-v1");
        mockMvc.perform(
                        post("/api/v1/auth/signup")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private String signupProof(String phone) throws Exception {
        Map<String, Object> consent =
                Map.of(
                        "phoneNumber",
                        phone,
                        "privacyCollectionAgreed",
                        true,
                        "privacyCollectionPolicyVersion",
                        "privacy-collection-v1");
        mockMvc.perform(
                        post("/api/v1/auth/phone-verifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(consent)))
                .andExpect(status().isAccepted());
        Map<String, Object> confirmation = new HashMap<>(consent);
        confirmation.put("verificationCode", deliveredCodes.get(protection.normalize(phone)));
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/auth/phone-verifications/confirm")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(mapper.writeValueAsString(confirmation)))
                        .andExpect(status().isOk())
                        .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("phoneVerificationToken")
                .asString();
    }

    private void requestRecoveryCode(String phone) throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/account-recovery/phone-verifications")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("phoneNumber", phone))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("인증 코드를 전송했습니다."))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private MvcResult confirmRecovery(String phone, String code, int expectedStatus)
            throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/account-recovery/phone-verifications/confirm")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "phoneNumber",
                                                        phone,
                                                        "verificationCode",
                                                        code))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private MvcResult resetPassword(
            String loginId, String token, String newPassword, int expectedStatus) throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/account-recovery/password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "loginId",
                                                        loginId,
                                                        "recoveryToken",
                                                        token,
                                                        "newPassword",
                                                        newPassword))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    /** 로그인 성공 시 refresh token 을 돌려준다. */
    private String login(String loginId, String password) throws Exception {
        MvcResult result = loginStatus(loginId, password, 200);
        return mapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("refreshToken")
                .asString();
    }

    private MvcResult loginStatus(String loginId, String password, int expectedStatus)
            throws Exception {
        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("loginId", loginId, "password", password))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
    }

    private void refresh(String refreshToken, int expectedStatus) throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/tokens/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("refreshToken", refreshToken))))
                .andExpect(status().is(expectedStatus));
    }

    private int sessionsAlive(String loginId) {
        Integer count =
                jdbc.queryForObject(
                        """
                        select count(*) from auth_session s join member m on m.id = s.member_id
                        where m.login_id = ? and s.revoked_at is null
                        """,
                        Integer.class,
                        loginId);
        return count == null ? 0 : count;
    }
}
