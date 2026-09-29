package com.meonggo.backend.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void businessExceptionKeepsDomainErrorCode() throws Exception {
        mockMvc.perform(get("/api/v1/ping/errors/business"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PING-001"))
                .andExpect(jsonPath("$.message").value("의도적으로 발생시킨 Ping 비즈니스 예외입니다."));
    }

    @Test
    void validationErrorReturnsFieldErrorsWithoutRejectedValue() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/ping/errors/validation")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"message\":\"\"}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("COMMON-001"))
                        .andExpect(jsonPath("$.data.fieldErrors[0].field").value("message"))
                        .andExpect(jsonPath("$.data.fieldErrors[0].reason").value("메시지는 필수입니다."))
                        .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("rejectedValue");
    }

    @Test
    void validMessagePassesValidation() throws Exception {
        mockMvc.perform(
                        post("/api/v1/ping/errors/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"valid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pong"));
    }

    @Test
    void malformedJsonReturnsCommon002() throws Exception {
        mockMvc.perform(
                        post("/api/v1/ping/errors/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-002"));
    }

    @Test
    void unsupportedMethodReturnsCommon405() throws Exception {
        mockMvc.perform(post("/api/v1/ping"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("COMMON-405"));
    }

    @Test
    @WithMockUser
    void unknownResourceReturnsCommon404() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON-404"));
    }

    @Test
    void unexpectedErrorHidesInternalDetails() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/ping/errors/unexpected"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("COMMON-500"))
                        .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
                        .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("IllegalStateException");
        assertThat(body).doesNotContain("의도적으로 발생시킨 예상하지 못한 예외");
    }
}
