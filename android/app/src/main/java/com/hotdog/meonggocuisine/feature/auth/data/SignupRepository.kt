package com.hotdog.meonggocuisine.feature.auth.data

sealed interface PhoneCodeRequestResult {
    data object Success : PhoneCodeRequestResult

    data class InvalidPhone(val message: String) : PhoneCodeRequestResult

    data class TemporarilyUnavailable(
        val message: String,
        val retryAfterSeconds: Int,
    ) : PhoneCodeRequestResult

    data class Failure(val message: String) : PhoneCodeRequestResult
}

sealed interface PhoneConfirmationResult {
    data class Success(
        val verificationToken: String,
        val expiresAt: String,
    ) : PhoneConfirmationResult

    data class InvalidCode(val message: String) : PhoneConfirmationResult

    data class PhoneInUse(val message: String) : PhoneConfirmationResult

    data class Failure(val message: String) : PhoneConfirmationResult
}

/** 아이디 중복 확인 결과. */
sealed interface LoginIdAvailabilityResult {
    data object Available : LoginIdAvailabilityResult

    data object Taken : LoginIdAvailabilityResult

    /** 구버전 서버에 이 엔드포인트가 없다. 가입을 막지 않고 안내만 한다. */
    data object NotSupportedYet : LoginIdAvailabilityResult

    data class Failure(val message: String) : LoginIdAvailabilityResult
}

sealed interface SignupResult {
    data object Success : SignupResult

    data class InvalidInput(
        val message: String,
        val fieldErrors: Map<String, String>,
    ) : SignupResult

    data class LoginIdInUse(val message: String) : SignupResult

    data class ConsentRequired(val message: String) : SignupResult

    data class PhoneInvalid(val message: String) : SignupResult

    data class PhoneInUse(val message: String) : SignupResult

    data class TemporarilyUnavailable(
        val message: String,
        val retryAfterSeconds: Int,
    ) : SignupResult

    data class Failure(val message: String) : SignupResult
}

interface SignupRepository {
    suspend fun checkLoginIdAvailability(loginId: String): LoginIdAvailabilityResult

    suspend fun requestPhoneCode(
        phoneNumber: String,
        privacyCollectionAgreed: Boolean,
    ): PhoneCodeRequestResult

    suspend fun confirmPhoneCode(
        phoneNumber: String,
        verificationCode: String,
        privacyCollectionAgreed: Boolean,
    ): PhoneConfirmationResult

    suspend fun signup(
        loginId: String,
        password: String,
        nickname: String,
        phoneNumber: String,
        phoneVerificationToken: String,
        privacyCollectionAgreed: Boolean,
    ): SignupResult
}
