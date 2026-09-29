package com.hotdog.meonggocuisine.feature.auth.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignupModelsTest {
    @Test
    fun `회원가입 요청에는 비밀번호 확인값을 포함하지 않는다`() {
        val request =
            SignupRequest(
                loginId = "mango206",
                password = "valid-password-123",
                nickname = "망고보호자",
                phoneNumber = "01012345678",
                phoneVerificationToken = "verification-token",
                privacyCollectionAgreed = true,
                privacyCollectionPolicyVersion = PRIVACY_COLLECTION_POLICY_VERSION,
            )

        val json = Json.encodeToString(request)

        assertFalse(json.contains("passwordConfirm"))
        assertTrue(json.contains("privacyCollectionPolicyVersion"))
    }
}
