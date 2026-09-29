package com.hotdog.meonggocuisine.feature.auth.ui.signup

import com.hotdog.meonggocuisine.feature.auth.data.LoginIdAvailabilityResult
import com.hotdog.meonggocuisine.feature.auth.data.PhoneCodeRequestResult
import com.hotdog.meonggocuisine.feature.auth.data.PhoneConfirmationResult
import com.hotdog.meonggocuisine.feature.auth.data.SignupRepository
import com.hotdog.meonggocuisine.feature.auth.data.SignupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignUpViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `NFC 정규화 결과가 다른 비밀번호 확인은 가입 API를 호출하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeSignupRepository()
            val viewModel = verifiedViewModel(repository)
            viewModel.onPasswordChange("valid-password-123")
            viewModel.onPasswordConfirmChange("different-password-123")

            viewModel.signup()

            assertEquals(0, repository.signupCount)
            assertEquals("비밀번호가 일치하지 않습니다.", viewModel.uiState.value.passwordConfirmError)
        }

    @Test
    fun `비밀번호의 공백 제어 형식 문자를 각각 거부한다`() =
        runTest(dispatcher) {
            val forbiddenPasswords =
                listOf(
                    "valid password 123",
                    "valid-password\n123",
                    "valid-password\u200B123",
                )

            forbiddenPasswords.forEach { password ->
                val repository = FakeSignupRepository()
                val viewModel = SignUpViewModel(repository)
                viewModel.enterBaseForm(password = password, passwordConfirm = password)

                viewModel.signup()

                assertNotNull(viewModel.uiState.value.passwordError)
                assertEquals(0, repository.signupCount)
            }
        }

    @Test
    fun `비밀번호에 공백을 치면 칸을 떠나기 전에도 바로 오류를 보인다`() =
        runTest(dispatcher) {
            val viewModel = SignUpViewModel(FakeSignupRepository())

            viewModel.onPasswordChange("abcd")
            assertNull(viewModel.uiState.value.passwordError) // 짧다는 잔소리는 아직 하지 않는다

            viewModel.onPasswordChange("abcd efgh")
            assertEquals("비밀번호에는 공백이나 제어 문자(탭·줄바꿈 등)를 사용할 수 없습니다.", viewModel.uiState.value.passwordError)

            viewModel.onPasswordChange("abcdefgh")
            assertNull(viewModel.uiState.value.passwordError)

            viewModel.onPasswordConfirmChange("abcd\tefgh")
            assertEquals("비밀번호에는 공백이나 제어 문자(탭·줄바꿈 등)를 사용할 수 없습니다.", viewModel.uiState.value.passwordConfirmError)
        }

    @Test
    fun `비밀번호는 NFC 기준 8자부터 64자까지 허용한다`() =
        runTest(dispatcher) {
            val repository = FakeSignupRepository()
            val viewModel = verifiedViewModel(repository)
            val composed = "가".repeat(8)
            val decomposed = "\u1100\u1161".repeat(8)
            viewModel.onPasswordChange(composed)
            viewModel.onPasswordConfirmChange(decomposed)

            viewModel.signup()
            advanceUntilIdle()

            assertEquals(1, repository.signupCount)
            assertEquals(composed, repository.lastPassword)
        }

    @Test
    fun `비밀번호가 NFC 기준 8자 미만이거나 64자를 초과하면 가입 API를 호출하지 않는다`() =
        runTest(dispatcher) {
            // 하한과 상한은 문구가 다르다 — 어느 쪽을 어겼는지 알려 줘야 고칠 수 있다.
            val cases =
                mapOf(
                    "a".repeat(7) to "비밀번호는 8자 이상이어야 합니다.",
                    "a".repeat(65) to "비밀번호는 64자 이하여야 합니다.",
                )
            cases.forEach { (password, expected) ->
                val repository = FakeSignupRepository()
                val viewModel = verifiedViewModel(repository)
                viewModel.onPasswordChange(password)
                viewModel.onPasswordConfirmChange(password)

                viewModel.signup()

                assertEquals(expected, viewModel.uiState.value.passwordError)
                assertEquals(0, repository.signupCount)
            }
        }

    @Test
    fun `필수 개인정보 동의가 없으면 가입 API를 호출하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeSignupRepository()
            val viewModel = verifiedViewModel(repository)
            viewModel.onPrivacyCollectionAgreementChange(false)

            viewModel.signup()

            assertEquals(0, repository.signupCount)
            assertEquals("개인정보 수집·이용에 동의해 주세요.", viewModel.uiState.value.privacyCollectionError)
        }

    @Test
    fun `필수 개인정보 동의 전에는 휴대전화 인증을 요청하지 않는다`() =
        runTest(dispatcher) {
            val repository = FakeSignupRepository()
            val viewModel = SignUpViewModel(repository)
            viewModel.enterBaseForm()

            assertFalse(viewModel.uiState.value.canRequestCode)
            viewModel.requestPhoneCode()
            advanceUntilIdle()
            assertEquals(0, repository.phoneCodeRequestCount)

            viewModel.onPrivacyCollectionAgreementChange(true)
            assertTrue(viewModel.uiState.value.canRequestCode)
            viewModel.requestPhoneCode()
            advanceUntilIdle()
            assertEquals(1, repository.phoneCodeRequestCount)
            assertTrue(repository.lastPhoneConsent == true)
        }

    @Test
    fun `휴대전화 인증 요청과 확인이 성공하면 가입할 수 있다`() =
        runTest(dispatcher) {
            val repository = FakeSignupRepository()
            val viewModel = SignUpViewModel(repository)
            viewModel.enterBaseForm()
            viewModel.onPrivacyCollectionAgreementChange(true)

            viewModel.requestPhoneCode()
            runCurrent()
            assertEquals(PhoneVerificationState.CODE_SENT, viewModel.uiState.value.phoneVerificationState)

            viewModel.onVerificationCodeChange("123456")
            viewModel.confirmPhoneCode()
            runCurrent()

            assertEquals(PhoneVerificationState.VERIFIED, viewModel.uiState.value.phoneVerificationState)
            assertTrue(viewModel.uiState.value.canSubmit)
            assertEquals("01012345678", repository.lastRequestedPhoneNumber)
            assertEquals("01012345678", repository.lastConfirmedPhoneNumber)
        }

    @Test
    fun `휴대전화 요청 제한은 서버의 재시도 시간을 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeSignupRepository(
                    phoneRequestResult = PhoneCodeRequestResult.TemporarilyUnavailable("잠시 후 다시 시도해 주세요.", 30),
                )
            val viewModel = SignUpViewModel(repository)
            viewModel.enterBaseForm()
            viewModel.onPrivacyCollectionAgreementChange(true)

            viewModel.requestPhoneCode()
            runCurrent()

            assertEquals("잠시 후 다시 시도해 주세요.", viewModel.uiState.value.requestError)
            assertEquals(30, viewModel.uiState.value.resendAfterSeconds)
            assertFalse(viewModel.uiState.value.canRequestCode)
        }

    @Test
    fun `이미 사용 중인 휴대전화 오류를 휴대전화 필드에 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeSignupRepository(
                    phoneConfirmationResult = PhoneConfirmationResult.PhoneInUse("이미 사용 중인 휴대전화 번호입니다."),
                )
            val viewModel = SignUpViewModel(repository)
            viewModel.enterBaseForm()
            viewModel.onPrivacyCollectionAgreementChange(true)
            viewModel.requestPhoneCode()
            runCurrent()
            viewModel.onVerificationCodeChange("123456")

            viewModel.confirmPhoneCode()
            runCurrent()

            assertEquals("이미 사용 중인 휴대전화 번호입니다.", viewModel.uiState.value.phoneNumberError)
            assertEquals(null, viewModel.uiState.value.requestError)
        }

    @Test
    fun `서버의 중복 아이디 오류를 아이디 필드에 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeSignupRepository(
                    signupResult = SignupResult.LoginIdInUse("이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요."),
                )
            val viewModel = verifiedViewModel(repository)

            viewModel.signup()
            advanceUntilIdle()

            assertEquals(
                "이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요.",
                viewModel.uiState.value.loginIdError,
            )
            assertEquals(null, viewModel.uiState.value.requestError)
        }

    @Test
    fun `중복 확인에서 이미 사용 중인 아이디면 다른 아이디 입력을 안내한다`() =
        runTest(dispatcher) {
            val repository = FakeSignupRepository()
            repository.loginIdAvailability = LoginIdAvailabilityResult.Taken
            val viewModel = SignUpViewModel(repository)
            viewModel.onLoginIdChange("mango206")

            viewModel.checkLoginIdAvailability()
            advanceUntilIdle()

            assertEquals(LoginIdCheckState.TAKEN, viewModel.uiState.value.loginIdCheck)
            assertEquals(
                "이미 사용 중인 아이디입니다. 다른 아이디를 입력해 주세요.",
                viewModel.uiState.value.loginIdError,
            )
        }

    @Test
    fun `가입 응답의 휴대전화 인증 필드 오류는 인증 상태를 초기화한다`() =
        runTest(dispatcher) {
            listOf("phoneNumber", "phoneVerificationToken").forEach { field ->
                val repository =
                    FakeSignupRepository(
                        signupResult =
                            SignupResult.InvalidInput(
                                message = "휴대전화 인증 정보를 확인해 주세요.",
                                fieldErrors = mapOf(field to "휴대전화 인증을 다시 진행해 주세요."),
                            ),
                    )
                val viewModel = verifiedViewModel(repository)

                viewModel.signup()
                advanceUntilIdle()

                assertEquals(1, repository.signupCount)
                assertEquals(PhoneVerificationState.IDLE, viewModel.uiState.value.phoneVerificationState)
                assertEquals("", viewModel.uiState.value.verificationCode)
                assertFalse(viewModel.uiState.value.canSubmit)

                viewModel.signup()
                advanceUntilIdle()

                assertEquals(1, repository.signupCount)
            }
        }

    @Test
    fun `가입 요청 중 다시 눌러도 API는 한 번만 호출한다`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<SignupResult>()
            val repository = FakeSignupRepository(signupGate = gate)
            val viewModel = verifiedViewModel(repository)

            viewModel.signup()
            runCurrent()
            assertTrue(viewModel.uiState.value.isSubmitting)
            assertFalse(viewModel.uiState.value.canSubmit)

            viewModel.signup()
            runCurrent()
            assertEquals(1, repository.signupCount)

            gate.complete(SignupResult.Success)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSubmitting)
            assertEquals(SignUpUiState(), viewModel.uiState.value)
        }

    @Test
    fun `인증한 휴대전화을 수정하면 인증 상태를 초기화한다`() =
        runTest(dispatcher) {
            val viewModel = verifiedViewModel(FakeSignupRepository())

            viewModel.onPhoneNumberChange("01098765432")

            assertEquals(PhoneVerificationState.IDLE, viewModel.uiState.value.phoneVerificationState)
            assertEquals("", viewModel.uiState.value.verificationCode)
            assertFalse(viewModel.uiState.value.canSubmit)
        }

    @Test
    fun `인증 요청 중 휴대전화을 바꾸면 이전 요청 결과를 반영하지 않는다`() =
        runTest(dispatcher) {
            val requestGate = CompletableDeferred<PhoneCodeRequestResult>()
            val repository = FakeSignupRepository(phoneRequestGate = requestGate)
            val viewModel = SignUpViewModel(repository)
            viewModel.enterBaseForm()
            viewModel.onPrivacyCollectionAgreementChange(true)
            viewModel.requestPhoneCode()
            runCurrent()
            assertTrue(viewModel.uiState.value.isRequestingCode)

            viewModel.onPhoneNumberChange("01098765432")
            requestGate.complete(PhoneCodeRequestResult.Success)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isRequestingCode)
            assertEquals(PhoneVerificationState.IDLE, viewModel.uiState.value.phoneVerificationState)
            assertEquals(0, viewModel.uiState.value.resendAfterSeconds)
            assertEquals(null, viewModel.uiState.value.requestError)
        }

    @Test
    fun `인증번호 확인 중 휴대전화을 바꾸면 이전 인증 결과를 반영하지 않는다`() =
        runTest(dispatcher) {
            val confirmationGate = CompletableDeferred<PhoneConfirmationResult>()
            val repository = FakeSignupRepository(phoneConfirmationGate = confirmationGate)
            val viewModel = SignUpViewModel(repository)
            viewModel.enterBaseForm()
            viewModel.onPrivacyCollectionAgreementChange(true)
            viewModel.requestPhoneCode()
            runCurrent()
            viewModel.onVerificationCodeChange("123456")
            viewModel.confirmPhoneCode()
            runCurrent()
            assertTrue(viewModel.uiState.value.isConfirmingCode)

            viewModel.onPhoneNumberChange("01098765432")
            confirmationGate.complete(
                PhoneConfirmationResult.Success(
                    verificationToken = "stale-token",
                    expiresAt = "2026-09-22T12:00:00Z",
                ),
            )
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isConfirmingCode)
            assertEquals(PhoneVerificationState.IDLE, viewModel.uiState.value.phoneVerificationState)
            assertFalse(viewModel.uiState.value.canSubmit)
        }

    private suspend fun TestScope.verifiedViewModel(repository: FakeSignupRepository): SignUpViewModel {
        val viewModel = SignUpViewModel(repository)
        viewModel.enterBaseForm()
        viewModel.onPrivacyCollectionAgreementChange(true)
        viewModel.requestPhoneCode()
        advanceUntilIdle()
        viewModel.onVerificationCodeChange("123456")
        viewModel.confirmPhoneCode()
        advanceUntilIdle()
        return viewModel
    }

    private fun SignUpViewModel.enterBaseForm(
        password: String = "valid-password-123",
        passwordConfirm: String = password,
    ) {
        onLoginIdChange("mango206")
        onPasswordChange(password)
        onPasswordConfirmChange(passwordConfirm)
        onNicknameChange("망고보호자")
        onPhoneNumberChange("01012345678")
    }

    private class FakeSignupRepository(
        private val signupGate: CompletableDeferred<SignupResult>? = null,
        private val signupResult: SignupResult = SignupResult.Success,
        private val phoneRequestGate: CompletableDeferred<PhoneCodeRequestResult>? = null,
        private val phoneConfirmationGate: CompletableDeferred<PhoneConfirmationResult>? = null,
        private val phoneRequestResult: PhoneCodeRequestResult = PhoneCodeRequestResult.Success,
        private val phoneConfirmationResult: PhoneConfirmationResult =
            PhoneConfirmationResult.Success(
                verificationToken = "verification-token",
                expiresAt = "2026-09-09T12:00:00Z",
            ),
    ) : SignupRepository {
        var loginIdCheckCount = 0
        var loginIdAvailability: LoginIdAvailabilityResult = LoginIdAvailabilityResult.Available

        override suspend fun checkLoginIdAvailability(loginId: String): LoginIdAvailabilityResult {
            loginIdCheckCount += 1
            return loginIdAvailability
        }

        var signupCount = 0
        var phoneCodeRequestCount = 0
        var lastPhoneConsent: Boolean? = null
        var lastPassword: String? = null
        var lastRequestedPhoneNumber: String? = null
        var lastConfirmedPhoneNumber: String? = null

        override suspend fun requestPhoneCode(
            phoneNumber: String,
            privacyCollectionAgreed: Boolean,
        ): PhoneCodeRequestResult {
            phoneCodeRequestCount += 1
            lastPhoneConsent = privacyCollectionAgreed
            lastRequestedPhoneNumber = phoneNumber
            return phoneRequestGate?.await() ?: phoneRequestResult
        }

        override suspend fun confirmPhoneCode(
            phoneNumber: String,
            verificationCode: String,
            privacyCollectionAgreed: Boolean,
        ): PhoneConfirmationResult {
            lastConfirmedPhoneNumber = phoneNumber
            return phoneConfirmationGate?.await() ?: phoneConfirmationResult
        }

        override suspend fun signup(
            loginId: String,
            password: String,
            nickname: String,
            phoneNumber: String,
            phoneVerificationToken: String,
            privacyCollectionAgreed: Boolean,
        ): SignupResult {
            signupCount += 1
            lastPassword = password
            return signupGate?.await() ?: signupResult
        }
    }
}
