package com.hotdog.meonggocuisine.feature.auth.ui

import com.hotdog.meonggocuisine.feature.auth.data.AuthRepository
import com.hotdog.meonggocuisine.feature.auth.data.LoginResult
import com.hotdog.meonggocuisine.feature.auth.data.TokenRefreshResult
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
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
    fun `빈 입력은 API를 호출하지 않고 필드 오류를 표시한다`() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository()
            val viewModel = LoginViewModel(repository)

            viewModel.login()

            assertEquals(0, repository.loginCount)
            assertEquals("아이디를 입력해 주세요.", viewModel.uiState.value.loginIdError)
            assertEquals("비밀번호를 입력해 주세요.", viewModel.uiState.value.passwordError)
        }

    @Test
    fun `서버 오류는 입력 오류와 구분해 요청 오류로 표시한다`() =
        runTest(dispatcher) {
            val repository =
                FakeAuthRepository(
                    result = LoginResult.ServerFailure("서버 오류가 발생했습니다."),
                )
            val viewModel = LoginViewModel(repository)
            viewModel.enterValidCredentials()

            viewModel.login()
            advanceUntilIdle()

            assertEquals("서버 오류가 발생했습니다.", viewModel.uiState.value.requestError)
            assertEquals(null, viewModel.uiState.value.loginIdError)
            assertEquals(null, viewModel.uiState.value.passwordError)
        }

    @Test
    fun `요청 중 로그인 버튼을 다시 눌러도 API는 한 번만 호출한다`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<LoginResult>()
            val repository = FakeAuthRepository(gate = gate)
            val viewModel = LoginViewModel(repository)
            viewModel.enterValidCredentials()

            viewModel.login()
            runCurrent()
            assertTrue(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.canSubmit)

            viewModel.login()
            runCurrent()
            assertEquals(1, repository.loginCount)

            gate.complete(LoginResult.Success)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isLoading)
        }

    private fun LoginViewModel.enterValidCredentials() {
        onLoginIdChange("mango206")
        onPasswordChange("valid-password")
    }

    private class FakeAuthRepository(
        private val result: LoginResult = LoginResult.Success,
        private val gate: CompletableDeferred<LoginResult>? = null,
    ) : AuthRepository {
        var loginCount = 0

        override suspend fun login(
            loginId: String,
            password: String,
        ): LoginResult {
            loginCount += 1
            return gate?.await() ?: result
        }

        override suspend fun refreshTokens(): TokenRefreshResult = TokenRefreshResult.Refreshed

        override suspend fun loadCurrentMember() = Unit
    }
}
