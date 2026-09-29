package com.hotdog.meonggocuisine.feature.auth.ui.recovery

import com.hotdog.meonggocuisine.feature.auth.data.AccountRecoveryRepository
import com.hotdog.meonggocuisine.feature.auth.data.PasswordResetResult
import com.hotdog.meonggocuisine.feature.auth.data.RecoveryCodeRequestResult
import com.hotdog.meonggocuisine.feature.auth.data.RecoveryConfirmationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class AccountRecoveryViewModelTest {
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
    fun `번호 인증을 마치면 아이디를 보여 주고 재설정 단계로 간다`() =
        runTest(dispatcher) {
            val repository = FakeRecoveryRepository()
            val viewModel = AccountRecoveryViewModel(repository)

            viewModel.onPhoneNumberChange("01012345678")
            viewModel.requestCode()
            runCurrent() // 카운트다운(가상 시간 60초)을 끝까지 돌리지 않고 응답 처리까지만
            assertTrue(viewModel.uiState.value.isCodeSent)
            assertEquals(60, viewModel.uiState.value.resendAfterSeconds)

            viewModel.onVerificationCodeChange("12ab34")
            assertEquals("1234", viewModel.uiState.value.verificationCode) // 숫자만
            viewModel.onVerificationCodeChange("123456")
            viewModel.confirmCode()
            advanceUntilIdle()

            assertEquals(RecoveryStep.FOUND, viewModel.uiState.value.step)
            assertEquals("mango206", viewModel.uiState.value.loginId)
            assertEquals("01012345678", repository.lastConfirmedPhone)
        }

    @Test
    fun `잘못된 번호는 요청하지 않고 서버의 코드 오류는 코드 칸에 붙는다`() =
        runTest(dispatcher) {
            val repository = FakeRecoveryRepository(confirm = RecoveryConfirmationResult.InvalidCode("인증 정보가 유효하지 않아요."))
            val viewModel = AccountRecoveryViewModel(repository)

            viewModel.onPhoneNumberChange("0212345678")
            viewModel.requestCode()
            advanceUntilIdle()
            assertEquals(0, repository.requestCount)
            assertNotNull(viewModel.uiState.value.phoneNumberError)

            viewModel.onPhoneNumberChange("01012345678")
            viewModel.requestCode()
            advanceUntilIdle()
            viewModel.onVerificationCodeChange("000000")
            viewModel.confirmCode()
            advanceUntilIdle()
            assertEquals(RecoveryStep.PHONE, viewModel.uiState.value.step)
            assertEquals("인증 정보가 유효하지 않아요.", viewModel.uiState.value.verificationCodeError)
        }

    @Test
    fun `비밀번호를 재설정하면 증명을 쓰고 완료 단계로 가며 비밀번호는 지운다`() =
        runTest(dispatcher) {
            val repository = FakeRecoveryRepository()
            val viewModel = verified(repository)

            viewModel.onNewPasswordChange("새로운-비밀번호-멍고!2026")
            viewModel.onNewPasswordConfirmChange("새로운-비밀번호-멍고!2026")
            viewModel.resetPassword()
            advanceUntilIdle()

            assertEquals(RecoveryStep.DONE, viewModel.uiState.value.step)
            assertEquals("mango206", repository.lastResetLoginId)
            assertEquals("pv1.token", repository.lastResetToken)
            assertEquals("", viewModel.uiState.value.newPassword)
            assertEquals("", viewModel.uiState.value.newPasswordConfirm)
        }

    @Test
    fun `짧거나 다른 확인값은 API 를 부르지 않고 공백은 치는 즉시 오류다`() =
        runTest(dispatcher) {
            val repository = FakeRecoveryRepository()
            val viewModel = verified(repository)

            viewModel.onNewPasswordChange("abc def1")
            assertNotNull(viewModel.uiState.value.newPasswordError)

            viewModel.onNewPasswordChange("short")
            viewModel.onNewPasswordConfirmChange("short2")
            viewModel.resetPassword()
            advanceUntilIdle()
            assertEquals(0, repository.resetCount)
            assertEquals("비밀번호는 8자 이상이어야 합니다.", viewModel.uiState.value.newPasswordError)
            assertEquals("비밀번호가 일치하지 않습니다.", viewModel.uiState.value.newPasswordConfirmError)
        }

    @Test
    fun `증명이 만료되면 처음 단계로 돌아가되 아이디는 남긴다`() =
        runTest(dispatcher) {
            val repository = FakeRecoveryRepository(reset = PasswordResetResult.ProofExpired("인증이 만료됐어요."))
            val viewModel = verified(repository)

            viewModel.onNewPasswordChange("새로운-비밀번호-멍고!2026")
            viewModel.onNewPasswordConfirmChange("새로운-비밀번호-멍고!2026")
            viewModel.resetPassword()
            advanceUntilIdle()

            assertEquals(RecoveryStep.PHONE, viewModel.uiState.value.step)
            assertEquals("인증이 만료됐어요.", viewModel.uiState.value.requestError)
            assertEquals("mango206", viewModel.uiState.value.loginId)
            assertFalse(viewModel.uiState.value.isCodeSent)
        }

    @Test
    fun `서버가 재시도 시간을 주면 그동안 다시 요청할 수 없다`() =
        runTest(dispatcher) {
            val repository =
                FakeRecoveryRepository(request = RecoveryCodeRequestResult.TemporarilyUnavailable("잠시 후 다시", retryAfterSeconds = 30))
            val viewModel = AccountRecoveryViewModel(repository)

            viewModel.onPhoneNumberChange("01012345678")
            viewModel.requestCode()
            runCurrent()

            assertEquals("잠시 후 다시", viewModel.uiState.value.requestError)
            assertFalse(viewModel.uiState.value.isCodeSent)
            assertFalse(viewModel.uiState.value.canRequestCode)
            assertNull(viewModel.uiState.value.phoneNumberError)
        }

    private fun verified(repository: FakeRecoveryRepository): AccountRecoveryViewModel {
        val viewModel = AccountRecoveryViewModel(repository)
        viewModel.onPhoneNumberChange("01012345678")
        viewModel.requestCode()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.onVerificationCodeChange("123456")
        viewModel.confirmCode()
        dispatcher.scheduler.advanceUntilIdle()
        check(viewModel.uiState.value.step == RecoveryStep.FOUND)
        return viewModel
    }

    private class FakeRecoveryRepository(
        private val request: RecoveryCodeRequestResult = RecoveryCodeRequestResult.Success,
        private val confirm: RecoveryConfirmationResult = RecoveryConfirmationResult.Success("mango206", "pv1.token"),
        private val reset: PasswordResetResult = PasswordResetResult.Success,
    ) : AccountRecoveryRepository {
        var requestCount = 0
        var resetCount = 0
        var lastConfirmedPhone: String? = null
        var lastResetLoginId: String? = null
        var lastResetToken: String? = null

        override suspend fun requestCode(phoneNumber: String): RecoveryCodeRequestResult {
            requestCount++
            return request
        }

        override suspend fun confirmCode(
            phoneNumber: String,
            verificationCode: String,
        ): RecoveryConfirmationResult {
            lastConfirmedPhone = phoneNumber
            return confirm
        }

        override suspend fun resetPassword(
            loginId: String,
            recoveryToken: String,
            newPassword: String,
        ): PasswordResetResult {
            resetCount++
            lastResetLoginId = loginId
            lastResetToken = recoveryToken
            return reset
        }
    }
}
