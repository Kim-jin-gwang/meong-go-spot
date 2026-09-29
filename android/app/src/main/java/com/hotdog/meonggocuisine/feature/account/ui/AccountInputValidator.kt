package com.hotdog.meonggocuisine.feature.account.ui

import java.text.Normalizer

/**
 * 마이페이지 입력 검증 — 회원가입(A1)과 같은 규칙이다 (docs/api-spec.md A7·A8).
 *
 * 서버가 최종 판정하지만, 길이·공백처럼 뻔한 것은 요청 전에 걸러 왕복을 줄인다. 비밀번호 차단 목록·로그인 ID 동일성은
 * 서버만 안다(`COMMON-001 newPassword` 로 돌아온다).
 */
internal object AccountInputValidator {
    fun validateNickname(value: String): String? {
        if (value.codePointCount() > MAX_RAW_CODE_POINTS) return "닉네임이 너무 깁니다."
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim()
        return when {
            normalized.isEmpty() -> "닉네임을 입력해 주세요."
            normalized.codePointCount() > MAX_NICKNAME_CODE_POINTS -> "닉네임은 30자 이하여야 합니다."
            normalized.codePoints().anyMatch(::isControlOrFormat) -> "닉네임에 사용할 수 없는 문자가 포함되어 있습니다."
            else -> null
        }
    }

    /** 현재 비밀번호는 형식을 따지지 않는다 — 비어 있는지만 본다. 틀린 값은 서버가 `AUTH-001` 로 알려 준다. */
    fun validateCurrentPassword(value: String): String? =
        when {
            value.isEmpty() -> "현재 비밀번호를 입력해 주세요."
            value.codePointCount() > MAX_RAW_CODE_POINTS -> "비밀번호가 너무 깁니다."
            else -> null
        }

    fun validateNewPassword(value: String): String? {
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

    /** 치는 도중 바로 보여 줄 오류 — 공백·제어 문자만. 회원가입([SignUpInputValidator])과 같은 이유·문구. */
    fun immediatePasswordError(value: String): String? =
        if (Normalizer.normalize(value, Normalizer.Form.NFC).codePoints().anyMatch(::isForbiddenPasswordCodePoint)) {
            FORBIDDEN_PASSWORD_CHARACTER
        } else {
            null
        }

    fun validatePasswordConfirm(
        password: String,
        confirm: String,
    ): String? =
        when {
            confirm.isEmpty() -> "새 비밀번호를 다시 입력해 주세요."
            Normalizer.normalize(password, Normalizer.Form.NFC) != Normalizer.normalize(confirm, Normalizer.Form.NFC) ->
                "비밀번호가 일치하지 않습니다."
            else -> null
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
    private const val MAX_NICKNAME_CODE_POINTS = 30
}
