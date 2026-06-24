package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSettingsManager(context: Context) {
    private val appContext = context.applicationContext

    private val sharedPreferences: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            "secret_shared_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        appContext.getSharedPreferences(
            "secret_shared_prefs_fallback",
            Context.MODE_PRIVATE
        )
    }

    fun saveTextApiKey(key: String) {
        runCatching {
            sharedPreferences.edit().putString("text_api_key", key).apply()
        }
    }

    fun getTextApiKey(): String {
        return runCatching {
            sharedPreferences.getString("text_api_key", "") ?: ""
        }.getOrDefault("")
    }

    fun clearTextApiKey() {
        runCatching {
            sharedPreferences.edit().remove("text_api_key").apply()
        }
    }
}
