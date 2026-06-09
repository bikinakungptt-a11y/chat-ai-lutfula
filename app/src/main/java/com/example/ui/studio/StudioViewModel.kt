package com.example.ui.studio

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class StudioUiState(
    val selectedTab: Int = 0, // 0: Photo, 1: Edit, 2: Video
    val prompt: String = "",
    val generatedMediaUrl: String? = null,
    val generatedVideoUrl: String? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val selectedImageUri: Uri? = null
)

class StudioViewModel(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val applicationContext: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index, error = null, generatedMediaUrl = null, generatedVideoUrl = null) }
    }

    fun updatePrompt(text: String) {
        _uiState.update { it.copy(prompt = text) }
    }

    fun selectImage(uri: Uri?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun generate() {
        val state = _uiState.value
        if (state.prompt.isBlank()) {
            _uiState.update { it.copy(error = "Prompt cannot be empty") }
            return
        }
        
        _uiState.update { it.copy(isGenerating = true, error = null, generatedMediaUrl = null, generatedVideoUrl = null) }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiKey = settingsRepository.apiKey.first()
                val baseUrl = settingsRepository.baseUrl.first()
                
                if (apiKey.isBlank()) {
                    _uiState.update { it.copy(isGenerating = false, error = "API Key is missing. Please configure in Settings.") }
                    return@launch
                }
                
                when (state.selectedTab) {
                    0 -> generateImage(apiKey, baseUrl, state.prompt)
                    1 -> editImage(apiKey, baseUrl, state.prompt, state.selectedImageUri)
                    2 -> generateVideo(apiKey, state.prompt)
                }

            } catch (e: Exception) {
                _uiState.update { it.copy(isGenerating = false, error = e.localizedMessage ?: "Generation failed") }
            }
        }
    }

    private fun generateImage(apiKey: String, baseUrl: String, prompt: String) {
        val endpoint = if (baseUrl.contains("openai.com")) "https://api.openai.com/v1/images/generations" else "${baseUrl.trimEnd('/')}/v1/images/generations"
        
        val json = JSONObject().apply {
            put("prompt", prompt)
            put("n", 1)
            put("size", "1024x1024")
        }
        
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
            
        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()
        
        if (response.isSuccessful && responseBody != null) {
            val jsonObject = JSONObject(responseBody)
            val url = jsonObject.optJSONArray("data")?.optJSONObject(0)?.optString("url")
            if (url != null) {
                _uiState.update { it.copy(isGenerating = false, generatedMediaUrl = url) }
            } else {
                _uiState.update { it.copy(isGenerating = false, error = "Failed to extract image URL from response") }
            }
        } else {
            _uiState.update { it.copy(isGenerating = false, error = "API Request failed: ${response.code} $responseBody") }
        }
    }
    
    private fun editImage(apiKey: String, baseUrl: String, prompt: String, imageUri: Uri?) {
        if (imageUri == null) {
            _uiState.update { it.copy(isGenerating = false, error = "Please select an image first") }
            return
        }
        
        val endpoint = if (baseUrl.contains("openai.com")) "https://api.openai.com/v1/images/edits" else "${baseUrl.trimEnd('/')}/v1/images/edits"
        
        // Convert URI to temporary file
        val tempFile = File.createTempFile("edit_img", ".png", applicationContext.cacheDir)
        applicationContext.contentResolver.openInputStream(imageUri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("image", "image.png", tempFile.asRequestBody("image/png".toMediaType()))
            .build()
            
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
            
        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string()
        
        if (response.isSuccessful && responseBody != null) {
            val jsonObject = JSONObject(responseBody)
            val url = jsonObject.optJSONArray("data")?.optJSONObject(0)?.optString("url")
            if (url != null) {
                _uiState.update { it.copy(isGenerating = false, generatedMediaUrl = url) }
            } else {
                _uiState.update { it.copy(isGenerating = false, error = "Failed to extract image URL") }
            }
        } else {
            _uiState.update { it.copy(isGenerating = false, error = "Edit failed (Note: Some APIs don't support edits): ${response.code}") }
        }
        
        tempFile.delete()
    }
    
    private fun generateVideo(apiKey: String, prompt: String) {
        // Mocking video generation since there's no standard free/easy endpoint to rely on here.
        // We'll simulate a processing delay and return a dummy result or explain it requires specific video keys.
        _uiState.update { it.copy(error = "Video API (e.g. Luma/Runway) requires custom backend integration. Simulating generation...") }
        kotlin.concurrent.thread {
            Thread.sleep(3000)
            _uiState.update { 
                it.copy(
                    isGenerating = false, 
                    error = null,
                    generatedVideoUrl = "https://www.w3schools.com/html/mov_bbb.mp4" // Dummy MP4
                ) 
            }
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val okHttpClient: OkHttpClient,
        private val context: android.content.Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StudioViewModel(settingsRepository, okHttpClient, context) as T
        }
    }
}
