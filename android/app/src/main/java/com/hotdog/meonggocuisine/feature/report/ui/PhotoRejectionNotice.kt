package com.hotdog.meonggocuisine.feature.report.ui

/**
 * 고른 사진 중 넣지 못한 것이 있을 때 띄우는 안내 창의 내용.
 *
 * 사진 줄 아래 빨간 한 줄은 첫 이유 하나만 보이고 눈에도 잘 띄지 않아, 왜 안 들어갔는지 모르겠다는
 * QA(2026-09-23)가 나왔다. 몇 장 중 몇 장이 빠졌고 이유가 무엇인지 창으로 올리고, 등록할 수 있는
 * 사진의 기준([PHOTO_REQUIREMENTS])을 늘 함께 적는다.
 */
data class PhotoRejectionNotice(
    val pickedCount: Int,
    val rejectedCount: Int,
    /** 이유별로 묶은 줄. 같은 이유가 여럿이면 뒤에 "(2장)" 처럼 장수를 붙인다. */
    val lines: List<String>,
)

/** 등록할 수 있는 사진의 기준. 값은 [com.hotdog.meonggocuisine.feature.report.data.PhotoInputInspector] · 서버(docs/photo-upload-policy.md)와 같아야 한다. */
const val PHOTO_REQUIREMENTS = "등록할 수 있는 사진: JPEG·PNG, 가로·세로 64px 이상, 사진당 10MB 이하, 최대 10장"

/**
 * [pickedCount] 장을 골랐는데 [rejections] 만큼 규격에 안 맞고 [overflowCount] 장이 장수 한도에 걸렸을 때의 안내.
 * 하나도 빠지지 않았으면 null — 창을 띄우지 않는다.
 */
fun buildPhotoRejectionNotice(
    pickedCount: Int,
    rejections: List<String>,
    overflowCount: Int,
): PhotoRejectionNotice? {
    if (rejections.isEmpty() && overflowCount <= 0) return null
    val lines =
        rejections
            .groupingBy { it }
            .eachCount()
            .map { (reason, count) -> if (count > 1) "$reason (${count}장)" else reason }
            .toMutableList()
    if (overflowCount > 0) lines += "사진은 최대 10장까지라 ${overflowCount}장은 넣지 않았어요."
    return PhotoRejectionNotice(pickedCount, rejections.size + overflowCount.coerceAtLeast(0), lines)
}
