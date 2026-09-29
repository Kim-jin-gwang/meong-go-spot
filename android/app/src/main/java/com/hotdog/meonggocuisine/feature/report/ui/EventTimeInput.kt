package com.hotdog.meonggocuisine.feature.report.ui

import java.util.Locale

enum class DayPeriod(val label: String) {
    AM("오전"),
    PM("오후"),
}

/**
 * 오전·오후 + 시 + 분으로 받는 사건 시각.
 *
 * 24시간 네 자리(`1730`)를 한 칸에 치게 했더니 QA(2026-09-21)에서 "입력하기 힘들다"는 말이 나왔다.
 * 사람은 시계를 오전·오후로 읽으므로 그대로 받고, 서버(P3 `eventTime`)에는 여전히 `HH:mm` 을 보낸다.
 *
 * 시·분은 사용자가 친 그대로(문자열) 들고 있다가 보낼 때 숫자로 바꾼다. `07` 처럼 앞에 0 을 붙여도 되고
 * `7` 만 쳐도 된다.
 */
data class EventTimeInput(
    val period: DayPeriod = DayPeriod.AM,
    val hour: String = "",
    val minute: String = "",
) {
    /** 1~12 안의 시. 비었거나 범위 밖이면 null. */
    val hourValue: Int? get() = hour.toIntOrNull()?.takeIf { it in 1..12 }

    /** 0~59 안의 분. 비었거나 범위 밖이면 null. */
    val minuteValue: Int? get() = minute.toIntOrNull()?.takeIf { it in 0..59 }

    /**
     * 더 쳐도 맞는 값이 될 수 없는 칸이 있는가 — 시 `13`·`00`, 분 `60`.
     *
     * 이런 값은 칸을 떠나기 전에 바로 빨갛게 보여 준다. 반대로 시 `0` 은 `07` 이 되는 중일 수 있어 기다린다.
     */
    val hasOutOfRangePart: Boolean
        get() =
            (hour.isNotEmpty() && hourValue == null && (hour.length == MAX_DIGITS || hour.toInt() > 12)) ||
                (minute.isNotEmpty() && minuteValue == null && (minute.length == MAX_DIGITS || minute.toInt() > 59))

    /** 서버에 보내는 `HH:mm`. 시·분이 비었거나 범위 밖이면 null. 오전 12시 = 00시, 오후 12시 = 12시. */
    fun toTime24(): String? {
        val hour12 = hourValue ?: return null
        val minute = minuteValue ?: return null
        val hour24 =
            when (period) {
                DayPeriod.AM -> hour12 % 12
                DayPeriod.PM -> hour12 % 12 + 12
            }
        return "%02d:%02d".format(Locale.ROOT, hour24, minute)
    }

    /**
     * 화면 검증 문구. [label] 은 "실종 시간"·"발견 시간" 처럼 칸 이름.
     *
     * 채웠는데 범위 밖인 칸을 먼저 짚는다 — 시 `13` 을 친 사람에게 "시간을 입력해 주세요" 는 엉뚱하다.
     * 틀린 칸이 없고 빈 칸만 있으면 채우라고 한다.
     */
    fun validationError(label: String): String? =
        when {
            hour.isNotEmpty() && hourValue == null -> "시는 1~12 사이로 입력해 주세요."
            minute.isNotEmpty() && minuteValue == null -> "분은 0~59 사이로 입력해 주세요."
            hour.isEmpty() || minute.isEmpty() -> "${label}을 입력해 주세요."
            else -> null
        }

    companion object {
        const val MAX_DIGITS = 2

        /** 시·분 칸에 들어온 글자에서 숫자 두 자리까지만 남긴다. */
        fun digits(raw: String): String = raw.filter(Char::isDigit).take(MAX_DIGITS)

        /** 서버·저장값의 `HH:mm` 을 화면 표기로 되돌린다. 형식이 아니면 null. */
        fun fromTime24(value: String): EventTimeInput? {
            val parts = value.split(':')
            val hour24 = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            val period = if (hour24 < 12) DayPeriod.AM else DayPeriod.PM
            val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
            return EventTimeInput(period, hour12.toString(), "%02d".format(Locale.ROOT, minute))
        }
    }
}
