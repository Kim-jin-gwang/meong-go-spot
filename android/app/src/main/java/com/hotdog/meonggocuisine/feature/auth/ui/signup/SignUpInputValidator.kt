package com.hotdog.meonggocuisine.feature.auth.ui.signup

import java.text.Normalizer
import java.util.Locale

internal data class SignUpValidationErrors(
    val loginId: String? = null,
    val password: String? = null,
    val passwordConfirm: String? = null,
    val nickname: String? = null,
    val phoneNumber: String? = null,
    val privacyCollection: String? = null,
) {
    val isValid: Boolean
        get() = listOf(loginId, password, passwordConfirm, nickname, phoneNumber, privacyCollection).all { it == null }
}

internal object SignUpInputValidator {
    const val VERIFICATION_CODE_LENGTH = 6
    const val RESEND_WAIT_SECONDS = 60

    fun validateForm(
        state: SignUpUiState,
        hasPhoneVerificationToken: Boolean,
    ): SignUpValidationErrors =
        SignUpValidationErrors(
            loginId = validateLoginId(state.loginId),
            password = validatePassword(state.password),
            passwordConfirm = validatePasswordConfirm(state.password, state.passwordConfirm),
            nickname = validateNickname(state.nickname),
            phoneNumber =
                validatePhoneNumber(state.phoneNumber)
                    ?: if (
                        state.phoneVerificationState != PhoneVerificationState.VERIFIED ||
                        !hasPhoneVerificationToken
                    ) {
                        "휴대전화 인증을 완료해 주세요."
                    } else {
                        null
                    },
            privacyCollection =
                if (state.privacyCollectionAgreed) null else "개인정보 수집·이용에 동의해 주세요.",
        )

    /**
     * 비밀번호 입력 중에 보여 줄 규칙.
     *
     * 상한(64자)은 말하지 않는다. 지키려고 세는 값이 아니라 넘길 일이 거의 없는 선이라,
     * 여기 적으면 정작 지켜야 할 하한이 묻힌다. 실제로 넘기면 그때 따로 알려 준다.
     */
    const val PASSWORD_RULE = "8자 이상, 공백과 제어 문자(탭·줄바꿈 등)는 쓸 수 없습니다."

    fun validatePhoneNumber(value: String): String? {
        return if (PHONE_NUMBER_PATTERN.matches(value)) {
            null
        } else {
            "010으로 시작하는 휴대전화 번호를 입력해 주세요."
        }
    }

    fun validateLoginId(value: String): String? {
        if (value.codePointCount() > MAX_RAW_CODE_POINTS) return "아이디가 너무 깁니다."
        val normalized =
            Normalizer.normalize(
                Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT),
                Normalizer.Form.NFC,
            )
        return when {
            normalized.isEmpty() -> "아이디를 입력해 주세요."
            normalized.codePointCount() > MAX_LOGIN_ID_CODE_POINTS -> "아이디는 50자 이하여야 합니다."
            normalized.codePoints().anyMatch(Character::isWhitespace) -> "아이디에는 공백을 사용할 수 없습니다."
            else -> null
        }
    }

    fun validatePassword(value: String): String? {
        if (value.codePointCount() > MAX_RAW_CODE_POINTS) return "비밀번호가 너무 깁니다."
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
        val length = normalized.codePointCount()
        return when {
            length < MIN_PASSWORD_CODE_POINTS -> "비밀번호는 8자 이상이어야 합니다."
            length > MAX_PASSWORD_CODE_POINTS -> "비밀번호는 64자 이하여야 합니다."
            normalized.codePoints().anyMatch(::isForbiddenPasswordCodePoint) -> FORBIDDEN_PASSWORD_CHARACTER
            else -> null
        }
    }

    /**
     * 치는 도중에도 바로 보여 줄 오류 — 공백·제어 문자만 본다.
     *
     * 길이 규칙은 다 치기 전엔 당연히 어긋나므로 칸을 떠날 때 본다. 하지만 공백은 점(●)으로 가려져 눌렀는지도
     * 모르는 채 넘어가기 쉬워(QA 2026-09-23 "공백도 비밀번호로 입력이 됨"), 친 순간 알려 준다. 자동으로 지우지는
     * 않는다 — 지우면 사용자가 친 것과 저장되는 것이 달라진다.
     */
    fun immediatePasswordError(value: String): String? =
        if (Normalizer.normalize(value, Normalizer.Form.NFC).codePoints().anyMatch(::isForbiddenPasswordCodePoint)) {
            FORBIDDEN_PASSWORD_CHARACTER
        } else {
            null
        }

    fun validatePasswordConfirm(
        password: String,
        passwordConfirm: String,
    ): String? =
        when {
            passwordConfirm.isEmpty() -> "비밀번호를 다시 입력해 주세요."
            Normalizer.normalize(password, Normalizer.Form.NFC) !=
                Normalizer.normalize(passwordConfirm, Normalizer.Form.NFC) -> "비밀번호가 일치하지 않습니다."
            else -> null
        }

    fun validateNickname(value: String): String? {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        return when {
            normalized.isEmpty() -> "닉네임을 입력해 주세요."
            normalized.codePointCount() > MAX_NICKNAME_CODE_POINTS -> "닉네임은 30자 이하여야 합니다."
            normalized.codePoints().anyMatch(::isControlOrFormat) ->
                "닉네임에 사용할 수 없는 문자가 포함되어 있습니다."
            else -> null
        }
    }

    private fun isForbiddenPasswordCodePoint(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || isControlOrFormat(codePoint)

    private fun isControlOrFormat(codePoint: Int): Boolean =
        Character.getType(codePoint) == Character.CONTROL.toInt() ||
            Character.getType(codePoint) == Character.FORMAT.toInt()

    private fun String.codePointCount(): Int = codePointCount(0, length)

    private const val FORBIDDEN_PASSWORD_CHARACTER = "비밀번호에는 공백이나 제어 문자(탭·줄바꿈 등)를 사용할 수 없습니다."
    private const val MAX_RAW_CODE_POINTS = 256
    private const val MIN_PASSWORD_CODE_POINTS = 8
    private const val MAX_PASSWORD_CODE_POINTS = 64
    private const val MAX_LOGIN_ID_CODE_POINTS = 50
    private const val MAX_NICKNAME_CODE_POINTS = 30
    private val PHONE_NUMBER_PATTERN = Regex("^(010[0-9]{8}|\\+8210[0-9]{8})$")
}
