package com.hotdog.meonggocuisine.feature.account.ui

import com.hotdog.meonggocuisine.feature.account.data.AccountField
import com.hotdog.meonggocuisine.feature.account.data.AccountRepository
import com.hotdog.meonggocuisine.feature.account.data.AccountResult
import com.hotdog.meonggocuisine.feature.account.data.ProfileResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PasswordChangeViewModelTest {
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
    fun `짧은 새 비밀번호와 불일치 확인값은 API 를 부르지 않고 칸별 오류를 보인다`() =
        runTest(dispatcher) {
            val repository = FakeAccountRepository()
            val viewModel = PasswordChangeViewModel(repository)
            viewModel.onCurrentPasswordChange("current-pw-206!")
            viewModel.onNewPasswordChange("short")
            viewModel.onNewPasswordConfirmChange("different")

            viewModel.submit()

            assertEquals(0, repository.changeCount)
            assertNull(viewModel.uiState.value.currentPasswordError)
            assertEquals("비밀번호는 8자 이상이어야 합니다.", viewModel.uiState.value.newPasswordError)
            assertEquals("비밀번호가 일치하지 않습니다.", viewModel.uiState.value.newPasswordConfirmError)
        }

    @Test
    fun `서버가 현재 비밀번호를 거부하면 그 칸에 오류가 붙고 입력은 남는다`() =
        runTest(dispatcher) {
            val repository =
                FakeAccountRepository(result = AccountResult.Rejected("현재 비밀번호가 올바르지 않습니다.", AccountField.CURRENT_PASSWORD))
            val viewModel = PasswordChangeViewModel(repository)
            viewModel.enterValidForm()

            viewModel.submit()
            advanceUntilIdle()

            assertEquals("현재 비밀번호가 올바르지 않습니다.", viewModel.uiState.value.currentPasswordError)
            assertNull(viewModel.uiState.value.requestError)
            assertEquals("next-pw-206!", viewModel.uiState.value.newPassword)
        }

    @Test
    fun `성공하면 비밀번호를 메모리에서 지우고 완료 이벤트를 보낸다`() =
        runTest(dispatcher) {
            val viewModel = PasswordChangeViewModel(FakeAccountRepository())
            viewModel.enterValidForm()

            viewModel.submit()
            advanceUntilIdle()

            assertEquals(PasswordChangeEvent.Changed, viewModel.events.first())
            assertEquals("", viewModel.uiState.value.currentPassword)
            assertEquals("", viewModel.uiState.value.newPassword)
        }

    @Test
    fun `요청 중 다시 눌러도 API 는 한 번만 호출한다`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<AccountResult>()
            val repository = FakeAccountRepository(gate = gate)
            val viewModel = PasswordChangeViewModel(repository)
            viewModel.enterValidForm()

            viewModel.submit()
            runCurrent()
            viewModel.submit()
            gate.complete(AccountResult.Success)
            advanceUntilIdle()

            assertEquals(1, repository.changeCount)
        }

    private fun PasswordChangeViewModel.enterValidForm() {
        onCurrentPasswordChange("current-pw-206!")
        onNewPasswordChange("next-pw-206!")
        onNewPasswordConfirmChange("next-pw-206!")
    }

    private class FakeAccountRepository(
        private val result: AccountResult = AccountResult.Success,
        private val gate: CompletableDeferred<AccountResult>? = null,
    ) : AccountRepository {
        var changeCount = 0

        override suspend fun loadProfile(): ProfileResult = ProfileResult.Loaded("망고보호자")

        override suspend fun changeNickname(nickname: String): AccountResult = AccountResult.Success

        override suspend fun changePassword(
            currentPassword: String,
            newPassword: String,
        ): AccountResult {
            changeCount += 1
            return gate?.await() ?: result
        }

        override suspend fun withdraw(currentPassword: String): AccountResult = AccountResult.Success

        override suspend fun logout() = Unit
    }
}
