package com.meonggo.backend.appversion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** V1 앱 버전 안내 — 인증 없이 읽고, 값은 배포 설정에서 온다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "APP_ANDROID_LATEST_VERSION_CODE=7",
            "APP_ANDROID_MIN_VERSION_CODE=5",
            "APP_ANDROID_STORE_URL=https://m.onestore.co.kr/v2/ko-kr/app/0001009297"
        })
class AppVersionApiTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void returnsConfiguredVersionsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/app/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.platform").value("android"))
                .andExpect(jsonPath("$.data.latestVersionCode").value(7))
                .andExpect(jsonPath("$.data.minSupportedVersionCode").value(5))
                .andExpect(
                        jsonPath("$.data.storeUrl")
                                .value("https://m.onestore.co.kr/v2/ko-kr/app/0001009297"));
        mockMvc.perform(get("/api/v1/app/version").param("platform", "Android"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsUnknownPlatform() throws Exception {
        mockMvc.perform(get("/api/v1/app/version").param("platform", "ios"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON-001"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("platform"));
    }
}
