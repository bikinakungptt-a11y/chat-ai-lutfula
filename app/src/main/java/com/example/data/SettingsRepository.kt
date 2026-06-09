package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val API_KEY = stringPreferencesKey("api_key")
    private val BASE_URL = stringPreferencesKey("base_url")
    private val MODEL = stringPreferencesKey("model")
    private val FIRECRAWL_API_KEY = stringPreferencesKey("firecrawl_api_key")

    val apiKey: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val baseUrl: Flow<String> = context.dataStore.data.map { it[BASE_URL] ?: "" }
    val model: Flow<String> = context.dataStore.data.map { it[MODEL] ?: "" }
    val firecrawlApiKey: Flow<String> = context.dataStore.data.map { it[FIRECRAWL_API_KEY] ?: "" }

    suspend fun saveSettings(key: String, url: String, modelName: String, firecrawlKey: String) {
        context.dataStore.edit { prefs ->
            prefs[API_KEY] = key
            prefs[BASE_URL] = url
            prefs[MODEL] = modelName
            prefs[FIRECRAWL_API_KEY] = firecrawlKey
        }
    }
}
