package com.hotdog.meonggocuisine.core.update

/** 앱이 시작할 때 보일 안내. */
enum class UpdatePrompt {
    /** 안내 없음. */
    NONE,

    /** 새 버전이 있다 — 닫을 수 있고 하루 한 번만 보인다. */
    RECOMMEND,

    /** 서버가 이 버전을 더 받지 않는다 — 닫을 수 없고 업데이트만 할 수 있다. */
    FORCE,
}

/**
 * 서버가 준 최신·최소 지원 `versionCode` 와 내 버전을 비교한다 (docs/api-spec.md V1 의 앱 판단 규칙).
 *
 * 0 은 "정하지 않음" 이라 그 조건은 건너뛴다. 권고는 오늘 이미 닫았으면 다시 보이지 않는다 — 매번 뜨면 사람은 읽지 않고 닫는다.
 */
fun decideUpdatePrompt(
    currentVersionCode: Int,
    latestVersionCode: Int,
    minSupportedVersionCode: Int,
    recommendationDismissedToday: Boolean,
): UpdatePrompt =
    when {
        minSupportedVersionCode > 0 && currentVersionCode < minSupportedVersionCode -> UpdatePrompt.FORCE
        latestVersionCode > 0 && currentVersionCode < latestVersionCode && !recommendationDismissedToday -> UpdatePrompt.RECOMMEND
        else -> UpdatePrompt.NONE
    }

/**
 * 원스토어 상품 페이지 링크에서 상품 ID 를 뽑아 원스토어 앱 딥링크를 만든다. 형식을 모르면 null — 그때는 웹 링크를 연다.
 *
 * `https://m.onestore.co.kr/v2/ko-kr/app/0001009297` → `onestore://common/product/0001009297`
 */
fun oneStoreDeepLink(storeUrl: String): String? {
    // 추적 인자(?utm_…)나 앵커가 붙어도 상품 ID 만 본다 (리뷰 지적).
    val cleanUrl = storeUrl.substringBefore('?').substringBefore('#').trimEnd('/')
    val productId = cleanUrl.substringAfterLast('/')
    if (!cleanUrl.contains("onestore.co.kr") || productId.isEmpty() || !productId.all(Char::isDigit)) return null
    return "onestore://common/product/$productId"
}
