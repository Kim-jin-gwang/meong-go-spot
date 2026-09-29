package com.meonggo.backend.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
class SecurityContractTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void pingAllowsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void privacyPolicyAllowsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("멍고반점 개인정보 처리방침")));
    }

    @Test
    void privacyPolicyHeadAllowsAnonymousRequests() throws Exception {
        mockMvc.perform(head("/privacy"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void privacyPolicySourceFileRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/privacy-policy.html"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTH-002"));
    }

    @Test
    void protectedResourceRejectsAnonymousRequestsWithSafeEnvelope() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/protected-resource"))
                        .andExpect(status().isUnauthorized())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value("AUTH-002"))
                        .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Exception", "stack", "protected-resource");
    }

    @Test
    @WithMockUser(roles = "USER")
    void forbiddenMethodReturnsSafeAccessDeniedEnvelope() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/ping/errors/forbidden"))
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value("AUTH-007"))
                        .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."))
                        .andExpect(jsonPath("$.data").doesNotExist())
                        .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("Exception", "stack", "forbidden");
    }
}
