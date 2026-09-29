package com.meonggo.backend.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meonggo.backend.auth.service.LoginService;
import com.meonggo.backend.auth.service.SessionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PushDeviceApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SessionService sessions;
    @MockitoBean private LoginService login;
    private long firstMember;
    private long secondMember;
    private String firstAccessToken;
    private String secondAccessToken;

    @BeforeEach
    void setup() {
        firstMember = member("첫 회원");
        secondMember = member("둘째 회원");
        firstAccessToken = bearer(firstMember);
        secondAccessToken = bearer(secondMember);
    }

    @Test
    void registersTokenOnCurrentSessionWithoutStoringPlaintext() throws Exception {
        UUID installation = UUID.randomUUID();
        String token = "private-fcm-registration-token";

        register(firstAccessToken, installation, token).andExpect(status().isNoContent());
        register(firstAccessToken, installation, token).andExpect(status().isNoContent());

        var registration = registration(firstMember);
        assertThat(registration.installationId()).isEqualTo(installation);
        assertThat(registration.platform()).isEqualTo("ANDROID");
        assertThat(registration.ciphertext()).startsWith("enc:v1:test-v1:").doesNotContain(token);
        assertThat(registration.lookupHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void atomicallyReassignsInstallationAndTokenToCurrentSession() throws Exception {
        UUID installation = UUID.randomUUID();
        String token = "shared-token";
        register(firstAccessToken, installation, token).andExpect(status().isNoContent());
        register(secondAccessToken, installation, token).andExpect(status().isNoContent());

        assertThat(registration(firstMember).installationId()).isNull();
        assertThat(registration(secondMember).installationId()).isEqualTo(installation);
    }

    @Test
    void unregistersOnlyCurrentSessionAndIsIdempotent() throws Exception {
        UUID firstInstallation = UUID.randomUUID();
        UUID secondInstallation = UUID.randomUUID();
        register(firstAccessToken, firstInstallation, "first-token")
                .andExpect(status().isNoContent());
        register(secondAccessToken, secondInstallation, "second-token")
                .andExpect(status().isNoContent());

        unregister(firstAccessToken, secondInstallation).andExpect(status().isNoContent());
        assertThat(registration(secondMember).installationId()).isEqualTo(secondInstallation);
        unregister(firstAccessToken, firstInstallation).andExpect(status().isNoContent());
        unregister(firstAccessToken, firstInstallation).andExpect(status().isNoContent());
        assertThat(registration(firstMember).installationId()).isNull();
    }

    @Test
    void rejectsInvalidOrAnonymousRegistration() throws Exception {
        UUID installation = UUID.randomUUID();
        mvc.perform(
                        put("/api/v1/members/me/push-devices/" + installation)
                                .header("Authorization", firstAccessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"platform\":\"IOS\",\"token\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"));
        register("", installation, "token").andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions register(
            String accessToken, UUID installation, String token) throws Exception {
        var request =
                put("/api/v1/members/me/push-devices/" + installation)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platform\":\"ANDROID\",\"token\":\"" + token + "\"}");
        if (!accessToken.isEmpty()) request.header("Authorization", accessToken);
        return mvc.perform(request);
    }

    private org.springframework.test.web.servlet.ResultActions unregister(
            String accessToken, UUID installation) throws Exception {
        return mvc.perform(
                delete("/api/v1/members/me/push-devices/" + installation)
                        .header("Authorization", accessToken));
    }

    private Registration registration(long memberId) {
        return jdbc.queryForObject(
                """
                select push_installation_id,push_platform,push_token_ciphertext,
                       push_token_lookup_hash
                from auth_session where member_id=? order by id desc limit 1
                """,
                (row, index) ->
                        new Registration(
                                row.getObject("push_installation_id", UUID.class),
                                row.getString("push_platform"),
                                row.getString("push_token_ciphertext"),
                                row.getString("push_token_lookup_hash")),
                memberId);
    }

    private String bearer(long memberId) {
        return "Bearer " + sessions.create(memberId).accessToken();
    }

    private long member(String nickname) {
        return jdbc.queryForObject(
                """
                insert into member(login_id,password_hash,nickname,status,created_at,updated_at,
                    phone_ciphertext,phone_lookup_hash,phone_verified_at,
                    privacy_collection_agreed,privacy_collection_policy_version,
                    privacy_collection_consented_at)
                values(?,'fixture',?,'ACTIVE',now(),now(),'private-phone',?,now(),
                    true,'privacy-collection-v1',now()) returning id
                """,
                Long.class,
                UUID.randomUUID().toString(),
                nickname,
                UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private record Registration(
            UUID installationId, String platform, String ciphertext, String lookupHash) {}
}
