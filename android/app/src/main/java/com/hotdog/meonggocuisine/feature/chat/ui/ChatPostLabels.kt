package com.hotdog.meonggocuisine.feature.chat.ui

import com.hotdog.meonggocuisine.core.text.speciesLabel

/**
 * 대화가 걸린 게시물을 부르는 말입니다.
 *
 * 채팅방은 게시물 하나마다 따로 열린다(`docs/erd.md` 규칙 13). 같은 사람과 게시물 둘로
 * 이야기하면 방도 둘이라, 상대 이름만으로는 어느 아이 이야기인지 알 수 없다.
 */
internal fun postTypeLabel(type: String): String =
    when (type) {
        "LOST" -> "잃어버렸어요"
        "SHELTERING" -> "보호하고 있어요"
        else -> "게시물"
    }

/**
 * "콩이 · 말티즈 수컷" 처럼 부른다.
 *
 * 이름과 품종은 비워 둘 수 있어 있는 것만 이어 붙인다. 품종을 모르면 축종으로 부르고, 그것도
 * 모르면 성별만 남는다. 빈 자리에 "이름 없는 아이" 같은 채움말을 넣지 않는다 — 이름 없이 올린
 * 게시물이 흔해서 그 말이 기본 모습이 된다.
 */
internal fun postDescriptor(
    name: String?,
    species: String?,
    breedName: String?,
    sex: String?,
): String {
    val kind =
        listOfNotNull(
            breedName?.takeIf(String::isNotBlank) ?: species?.let(::speciesLabel),
            sexLabel(sex),
        ).joinToString(" ")
    return listOfNotNull(name?.takeIf(String::isNotBlank), kind.takeIf(String::isNotBlank))
        .joinToString(" · ")
}

private fun sexLabel(sex: String?): String? =
    when (sex) {
        "MALE" -> "수컷"
        "FEMALE" -> "암컷"
        else -> null
    }
