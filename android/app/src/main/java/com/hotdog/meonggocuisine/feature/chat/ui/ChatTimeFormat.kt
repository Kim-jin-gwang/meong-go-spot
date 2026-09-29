package com.hotdog.meonggocuisine.feature.chat.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

internal fun formatChatTime(
    createdAt: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): String {
    val dateTime = chatDateTime(createdAt, zoneId) ?: return ""
    return if (dateTime.toLocalDate() == today) {
        formatClock(dateTime)
    } else {
        "${dateTime.monthValue}월 ${dateTime.dayOfMonth}일"
    }
}

/**
 * 말풍선 옆에 붙는 시각입니다. 날짜는 붙이지 않는다 — 날짜는 [formatChatDate] 가 줄 사이에
 * 한 번만 알린다.
 */
internal fun formatChatClock(
    createdAt: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = chatDateTime(createdAt, zoneId)?.let(::formatClock).orEmpty()

/** 메시지가 놓인 날짜. 줄 사이에 날짜를 끼울 자리를 찾는 데 쓴다. */
internal fun chatDate(
    createdAt: String?,
    zoneId: ZoneId = ZoneId.systemDefault(),
): LocalDate? = chatDateTime(createdAt, zoneId)?.toLocalDate()

/** 줄 사이에 끼우는 날짜입니다. 오늘과 어제는 날짜 대신 그렇게 부른다. */
internal fun formatChatDate(
    date: LocalDate,
    today: LocalDate = LocalDate.now(),
    locale: Locale = Locale.KOREAN,
): String =
    when (date) {
        today -> "오늘"
        today.minusDays(1) -> "어제"
        else -> {
            val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            val year = if (date.year == today.year) "" else "${date.year}년 "
            "$year${date.monthValue}월 ${date.dayOfMonth}일 $weekday"
        }
    }

private fun chatDateTime(
    createdAt: String?,
    zoneId: ZoneId,
): LocalDateTime? {
    if (createdAt == null) return null
    return runCatching { LocalDateTime.ofInstant(Instant.parse(createdAt), zoneId) }.getOrNull()
}

private fun formatClock(dateTime: LocalDateTime): String {
    val period = if (dateTime.hour < 12) "오전" else "오후"
    val hour =
        when (val value = dateTime.hour % 12) {
            0 -> 12
            else -> value
        }
    return "$period $hour:%02d".format(dateTime.minute)
}
