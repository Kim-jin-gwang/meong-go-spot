package com.hotdog.meonggocuisine.core.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** V1 을 부른다. 실패는 null — 업데이트 확인이 앱 진입을 막으면 안 된다. */
interface AppVersionRepository {
    suspend fun fetch(): AppVersionResponse?
}

class DefaultAppVersionRepository
    @Inject
    constructor(
        private val api: AppVersionApi,
    ) : AppVersionRepository {
        override suspend fun fetch(): AppVersionResponse? =
            runCatching {
                val response = api.version()
                response.body()?.takeIf { response.isSuccessful }?.data
            }.getOrNull()
    }

/** 권고 안내를 닫은 날을 기억한다 — 같은 날에는 다시 보이지 않는다. */
interface UpdatePromptStore {
    fun dismissedOn(): LocalDate?

    fun markDismissed(date: LocalDate)
}

@Singleton
class PreferencesUpdatePromptStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : UpdatePromptStore {
        private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        override fun dismissedOn(): LocalDate? =
            preferences.getString(KEY_DISMISSED_ON, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        override fun markDismissed(date: LocalDate) {
            preferences.edit().putString(KEY_DISMISSED_ON, date.toString()).apply()
        }

        private companion object {
            const val PREFERENCES_NAME = "update_prompt"
            const val KEY_DISMISSED_ON = "recommendation_dismissed_on"
        }
    }
