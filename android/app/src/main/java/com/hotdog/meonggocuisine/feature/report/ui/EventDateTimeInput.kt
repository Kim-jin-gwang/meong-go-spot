package com.hotdog.meonggocuisine.feature.report.ui

/**
 * 사건 날짜 입력을 숫자만 받아 구분선을 채워 넣습니다.
 *
 * 숫자 키보드에는 `-` 가 없어서, 구분선을 사용자가 넣게 두면 키보드를 바꿔 가며 쳐야 했습니다.
 *
 * 구분선은 다음 숫자가 들어올 때 비로소 붙입니다. 끝에 구분선이 남지 않으므로 지우기를 누르면
 * 항상 숫자가 한 자씩 지워집니다.
 *
 * 시간은 [EventTimeInput] 이 오전·오후 + 시 + 분으로 따로 받습니다.
 */
object EventDateTimeInput {
    const val DATE_DIGITS = 8

    /** `20260909` → `2026-09-09` */
    fun date(raw: String): String {
        val digits = raw.digits(DATE_DIGITS)
        return buildString {
            append(digits.take(4))
            if (digits.length > 4) append('-').append(digits.substring(4, minOf(6, digits.length)))
            if (digits.length > 6) append('-').append(digits.substring(6))
        }
    }

    fun digitCount(value: String): Int = value.count(Char::isDigit)

    private fun String.digits(limit: Int): String = filter(Char::isDigit).take(limit)
}
