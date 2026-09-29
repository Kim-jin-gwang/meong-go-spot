package com.hotdog.meonggocuisine.feature.community.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 전국을 뜻하는 저장 코드. 서버에는 regionCode 를 보내지 않는다 (P1·D3: 없음 = 전국).
 * 시·도 전체는 2자리 접두(예 "11" = 서울특별시), 시·군·구는 5자리 — 서버가 세 범위를 같은 규칙으로 받는다.
 */
const val NATIONWIDE_REGION_CODE = "00"

data class SelectedRegion(
    val code: String,
    val name: String,
) {
    /** 게시물 등록처럼 시·군·구 코드가 꼭 필요한 곳에서 본다. 전국·시·도 전체는 아니다. */
    val isDistrict: Boolean get() = code.length == 5
}

/** 서버 P1·D3 에 보내는 값 — 전국은 null(파라미터 생략), 나머지는 저장 코드 그대로. */
fun String?.asApiRegionCode(): String? = this?.takeUnless { it == NATIONWIDE_REGION_CODE }

interface RegionStore {
    val selectedRegion: StateFlow<SelectedRegion?>

    fun select(region: SelectedRegion)

    fun clear()
}

@Singleton
class SelectedRegionStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : RegionStore {
        private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        private val mutableSelectedRegion = MutableStateFlow(readRegion())

        override val selectedRegion: StateFlow<SelectedRegion?> = mutableSelectedRegion.asStateFlow()

        override fun select(region: SelectedRegion) {
            preferences.edit().putString(KEY_CODE, region.code).putString(KEY_NAME, region.name).apply()
            mutableSelectedRegion.value = region
        }

        override fun clear() {
            preferences.edit().remove(KEY_CODE).remove(KEY_NAME).apply()
            mutableSelectedRegion.value = null
        }

        private fun readRegion(): SelectedRegion? {
            val code = preferences.getString(KEY_CODE, null) ?: return null
            val name = preferences.getString(KEY_NAME, null) ?: return null
            return SelectedRegion(code = code, name = name)
        }

        private companion object {
            const val PREFERENCES_NAME = "community_preferences"
            const val KEY_CODE = "selected_region_code"
            const val KEY_NAME = "selected_region_name"
        }
    }

/** 고른 적이 없을 때 쓰는 기본 지역입니다. 목록은 이걸로 먼저 보여 주고, 좁히는 건 사용자가 정한다. */
val NationwideRegion = SelectedRegion(code = NATIONWIDE_REGION_CODE, name = "전국")
