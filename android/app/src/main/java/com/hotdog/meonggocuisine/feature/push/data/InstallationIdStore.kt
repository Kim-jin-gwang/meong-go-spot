package com.hotdog.meonggocuisine.feature.push.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface InstallationIdStore {
    fun getOrCreate(): String
}

@Singleton
class SharedPreferencesInstallationIdStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : InstallationIdStore {
        private val preferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        @Synchronized
        override fun getOrCreate(): String {
            preferences.getString(KEY_INSTALLATION_ID, null)?.let { return it }
            return UUID.randomUUID().toString().also { installationId ->
                preferences.edit().putString(KEY_INSTALLATION_ID, installationId).commit()
            }
        }

        private companion object {
            const val PREFERENCES_NAME = "meonggocuisine_push_device"
            const val KEY_INSTALLATION_ID = "installation_id"
        }
    }
