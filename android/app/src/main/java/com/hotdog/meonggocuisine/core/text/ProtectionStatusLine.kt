package com.hotdog.meonggocuisine.core.text

/**
 * 특징 글 맨 앞에 얹는 `현재 보호 상태: …` 한 줄을 붙이고 뗍니다.
 *
 * 서버에는 보호 상태를 담을 칸이 없어 특징 글에 한 줄로 실어 보낸다. 그래서 이 한 줄은
 * 사용자가 쓴 글이 아니라 약속된 머리글이고, 글을 보여 주거나 고치게 하는 쪽은 언제나 이걸
 * 떼고 다뤄야 한다. 붙이는 곳과 떼는 곳이 따로 놀면 수정 화면처럼 사용자가 쓴 적 없는 줄이
 * 입력칸에 그대로 나오고, 그 줄을 지우는 순간 보호 상태가 사라진다 — 그래서 한 곳에 모은다.
 */
object ProtectionStatusLine {
    private const val PREFIX = "현재 보호 상태:"

    /** 저장된 글에서 보호 상태만. 머리글이 없으면 null. */
    fun statusOf(featureText: String?): String? =
        featureText
            ?.lines()
            ?.firstOrNull { it.startsWith(PREFIX) }
            ?.substringAfter(PREFIX)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    /** 머리글을 뺀 사용자의 글. 남는 게 없으면 null. */
    fun withoutStatus(featureText: String?): String? =
        featureText
            ?.lines()
            ?.filterNot { it.startsWith(PREFIX) }
            ?.joinToString("\n")
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    /**
     * 보호 상태와 사용자의 글을 서버로 보낼 한 덩이로.
     *
     * 상태에 줄바꿈이 들어가면 되읽을 때 어긋나므로 한 줄로 눌러 담는다.
     */
    fun join(
        status: String,
        featureText: String,
    ): String =
        listOfNotNull(
            status.lines().joinToString(" ").trim().takeIf(String::isNotEmpty)?.let { "$PREFIX $it" },
            featureText.trim().takeIf(String::isNotEmpty),
        ).joinToString("\n")
}
