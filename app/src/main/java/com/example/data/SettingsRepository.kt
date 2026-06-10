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
    private val TEXT_PATH = stringPreferencesKey("text_path")
    private val MODEL = stringPreferencesKey("model")
    private val FIRECRAWL_API_KEY = stringPreferencesKey("firecrawl_api_key")
    private val TEXT_PROVIDER = stringPreferencesKey("text_provider")
    private val ASSISTANT_LANGUAGE_PREFERENCE = stringPreferencesKey("assistantLanguagePreference")

    // Media Generation Settings
    // Create Photo
    private val CREATE_PHOTO_PROVIDER = stringPreferencesKey("create_photo_provider")
    private val CREATE_PHOTO_API_KEY = stringPreferencesKey("create_photo_api_key")
    private val CREATE_PHOTO_BASE_URL = stringPreferencesKey("create_photo_base_url")
    private val CREATE_PHOTO_ENDPOINT = stringPreferencesKey("create_photo_endpoint")
    private val CREATE_PHOTO_MODEL = stringPreferencesKey("create_photo_model")
    private val CREATE_PHOTO_FORMAT = stringPreferencesKey("create_photo_format")

    // Edit Photo
    private val EDIT_PHOTO_PROVIDER = stringPreferencesKey("edit_photo_provider")
    private val EDIT_PHOTO_API_KEY = stringPreferencesKey("edit_photo_api_key")
    private val EDIT_PHOTO_BASE_URL = stringPreferencesKey("edit_photo_base_url")
    private val EDIT_PHOTO_ENDPOINT = stringPreferencesKey("edit_photo_endpoint")
    private val EDIT_PHOTO_MODEL = stringPreferencesKey("edit_photo_model")
    private val EDIT_PHOTO_FORMAT = stringPreferencesKey("edit_photo_format")
    private val EDIT_PHOTO_IMAGE_FORMAT = stringPreferencesKey("edit_photo_image_format")

    // Photo to Video
    private val PHOTO_VIDEO_PROVIDER = stringPreferencesKey("photo_video_provider")
    private val PHOTO_VIDEO_API_KEY = stringPreferencesKey("photo_video_api_key")
    private val PHOTO_VIDEO_BASE_URL = stringPreferencesKey("photo_video_base_url")
    private val PHOTO_VIDEO_CREATE_ENDPOINT = stringPreferencesKey("photo_video_create_endpoint")
    private val PHOTO_VIDEO_STATUS_ENDPOINT = stringPreferencesKey("photo_video_status_endpoint")
    private val PHOTO_VIDEO_RESULT_ENDPOINT = stringPreferencesKey("photo_video_result_endpoint")
    private val PHOTO_VIDEO_MODEL = stringPreferencesKey("photo_video_model")
    private val PHOTO_VIDEO_FORMAT = stringPreferencesKey("photo_video_format")
    private val PHOTO_VIDEO_IMAGE_FORMAT = stringPreferencesKey("photo_video_image_format")
    private val PHOTO_VIDEO_DURATION = stringPreferencesKey("photo_video_duration")

    val textProvider: Flow<String> = context.dataStore.data.map { it[TEXT_PROVIDER] ?: "" }
    val apiKey: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val baseUrl: Flow<String> = context.dataStore.data.map { it[BASE_URL] ?: "" }
    val textPath: Flow<String> = context.dataStore.data.map { it[TEXT_PATH] ?: "/chat/completions" }
    val model: Flow<String> = context.dataStore.data.map { it[MODEL] ?: "" }
    val firecrawlApiKey: Flow<String> = context.dataStore.data.map { it[FIRECRAWL_API_KEY] ?: "" }

    val createPhotoProvider: Flow<String> = context.dataStore.data.map { it[CREATE_PHOTO_PROVIDER] ?: "" }
    val createPhotoApiKey: Flow<String> = context.dataStore.data.map { it[CREATE_PHOTO_API_KEY] ?: "" }
    val createPhotoBaseUrl: Flow<String> = context.dataStore.data.map { it[CREATE_PHOTO_BASE_URL] ?: "" }
    val createPhotoEndpoint: Flow<String> = context.dataStore.data.map { it[CREATE_PHOTO_ENDPOINT] ?: "" }
    val createPhotoModel: Flow<String> = context.dataStore.data.map { it[CREATE_PHOTO_MODEL] ?: "" }
    val createPhotoFormat: Flow<String> = context.dataStore.data.map { it[CREATE_PHOTO_FORMAT] ?: "JSON" }

    val editPhotoProvider: Flow<String> = context.dataStore.data.map { it[EDIT_PHOTO_PROVIDER] ?: "" }
    val editPhotoApiKey: Flow<String> = context.dataStore.data.map { it[EDIT_PHOTO_API_KEY] ?: "" }
    val editPhotoBaseUrl: Flow<String> = context.dataStore.data.map { it[EDIT_PHOTO_BASE_URL] ?: "" }
    val editPhotoEndpoint: Flow<String> = context.dataStore.data.map { it[EDIT_PHOTO_ENDPOINT] ?: "" }
    val editPhotoModel: Flow<String> = context.dataStore.data.map { it[EDIT_PHOTO_MODEL] ?: "" }
    val editPhotoFormat: Flow<String> = context.dataStore.data.map { it[EDIT_PHOTO_FORMAT] ?: "JSON" }
    val editPhotoImageFormat: Flow<String> = context.dataStore.data.map { it[EDIT_PHOTO_IMAGE_FORMAT] ?: "base64" }

    val photoVideoProvider: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_PROVIDER] ?: "" }
    val photoVideoApiKey: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_API_KEY] ?: "" }
    val photoVideoBaseUrl: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_BASE_URL] ?: "" }
    val photoVideoCreateEndpoint: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_CREATE_ENDPOINT] ?: "" }
    val photoVideoStatusEndpoint: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_STATUS_ENDPOINT] ?: "" }
    val photoVideoResultEndpoint: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_RESULT_ENDPOINT] ?: "" }
    val photoVideoModel: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_MODEL] ?: "" }
    val photoVideoFormat: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_FORMAT] ?: "JSON" }
    val photoVideoImageFormat: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_IMAGE_FORMAT] ?: "base64" }
    val photoVideoDuration: Flow<String> = context.dataStore.data.map { it[PHOTO_VIDEO_DURATION] ?: "5" }

    val assistantLanguagePreference: Flow<String> = context.dataStore.data.map { it[ASSISTANT_LANGUAGE_PREFERENCE] ?: "id" }

    suspend fun saveSettings(provider: String, key: String, url: String, path: String, modelName: String, firecrawlKey: String) {
        context.dataStore.edit { prefs ->
            prefs[TEXT_PROVIDER] = provider
            prefs[API_KEY] = key
            prefs[BASE_URL] = url
            prefs[TEXT_PATH] = path
            prefs[MODEL] = modelName
            prefs[FIRECRAWL_API_KEY] = firecrawlKey
        }
    }

    suspend fun saveCreatePhotoSettings(provider: String, apiKey: String, baseUrl: String, endpoint: String, model: String, format: String) {
        context.dataStore.edit { prefs ->
            prefs[CREATE_PHOTO_PROVIDER] = provider
            prefs[CREATE_PHOTO_API_KEY] = apiKey
            prefs[CREATE_PHOTO_BASE_URL] = baseUrl
            prefs[CREATE_PHOTO_ENDPOINT] = endpoint
            prefs[CREATE_PHOTO_MODEL] = model
            prefs[CREATE_PHOTO_FORMAT] = format
        }
    }

    suspend fun saveEditPhotoSettings(provider: String, apiKey: String, baseUrl: String, endpoint: String, model: String, format: String, imageFormat: String) {
        context.dataStore.edit { prefs ->
            prefs[EDIT_PHOTO_PROVIDER] = provider
            prefs[EDIT_PHOTO_API_KEY] = apiKey
            prefs[EDIT_PHOTO_BASE_URL] = baseUrl
            prefs[EDIT_PHOTO_ENDPOINT] = endpoint
            prefs[EDIT_PHOTO_MODEL] = model
            prefs[EDIT_PHOTO_FORMAT] = format
            prefs[EDIT_PHOTO_IMAGE_FORMAT] = imageFormat
        }
    }

    suspend fun savePhotoVideoSettings(provider: String, apiKey: String, baseUrl: String, createEndpoint: String, statusEndpoint: String, resultEndpoint: String, model: String, format: String, imageFormat: String, duration: String) {
        context.dataStore.edit { prefs ->
            prefs[PHOTO_VIDEO_PROVIDER] = provider
            prefs[PHOTO_VIDEO_API_KEY] = apiKey
            prefs[PHOTO_VIDEO_BASE_URL] = baseUrl
            prefs[PHOTO_VIDEO_CREATE_ENDPOINT] = createEndpoint
            prefs[PHOTO_VIDEO_STATUS_ENDPOINT] = statusEndpoint
            prefs[PHOTO_VIDEO_RESULT_ENDPOINT] = resultEndpoint
            prefs[PHOTO_VIDEO_MODEL] = model
            prefs[PHOTO_VIDEO_FORMAT] = format
            prefs[PHOTO_VIDEO_IMAGE_FORMAT] = imageFormat
            prefs[PHOTO_VIDEO_DURATION] = duration
        }
    }
}
