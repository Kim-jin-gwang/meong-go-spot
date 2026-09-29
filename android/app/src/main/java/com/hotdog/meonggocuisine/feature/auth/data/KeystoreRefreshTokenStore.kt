package com.hotdog.meonggocuisine.feature.auth.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystoreRefreshTokenStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : RefreshTokenStore {
        private val preferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        override fun save(refreshToken: String) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
            val value = "${cipher.iv.encode()}.${encrypted.encode()}"
            check(preferences.edit().putString(REFRESH_TOKEN_KEY, value).commit()) {
                "Failed to persist encrypted refresh token"
            }
        }

        override fun read(): String? {
            val stored = preferences.getString(REFRESH_TOKEN_KEY, null) ?: return null
            return runCatching {
                val (iv, encrypted) = stored.split('.', limit = 2)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv.decode()))
                cipher.doFinal(encrypted.decode()).toString(Charsets.UTF_8)
            }.getOrElse {
                clear()
                null
            }
        }

        override fun clear() {
            preferences.edit().remove(REFRESH_TOKEN_KEY).commit()
        }

        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }

        private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.NO_WRAP)

        private fun String.decode(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

        private companion object {
            const val ANDROID_KEY_STORE = "AndroidKeyStore"
            const val KEY_ALIAS = "meonggocuisine_refresh_token_key"
            const val PREFERENCES_NAME = "meonggocuisine_auth_tokens"
            const val REFRESH_TOKEN_KEY = "refresh_token_ciphertext"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }
