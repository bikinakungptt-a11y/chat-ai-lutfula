package com.example.ui.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import com.example.data.MicrosoftAuthService
import com.example.network.ChatRequest
import com.example.network.ChatMessage
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.microsoft.identity.client.IAccount

data class SettingsUiState(
    val textProvider: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val textPath: String = "/chat/completions",
    val modelName: String = "",
    val firecrawlApiKey: String = "",
    val isSaved: Boolean = false,
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val testError: String? = null,
    val requireValidation: Boolean = false,
    val validationError: String? = null,
    val microsoftAccount: IAccount? = null,
    
    // Create Photo
    val createPhotoProvider: String = "",
    val createPhotoApiKey: String = "",
    val createPhotoBaseUrl: String = "",
    val createPhotoEndpoint: String = "",
    val createPhotoModel: String = "",
    val createPhotoFormat: String = "JSON",

    // Edit Photo
    val editPhotoProvider: String = "",
    val editPhotoApiKey: String = "",
    val editPhotoBaseUrl: String = "",
    val editPhotoEndpoint: String = "",
    val editPhotoModel: String = "",
    val editPhotoFormat: String = "JSON",
    val editPhotoImageFormat: String = "base64",

    // Photo to Video
    val photoVideoProvider: String = "",
    val photoVideoApiKey: String = "",
    val photoVideoBaseUrl: String = "",
    val photoVideoCreateEndpoint: String = "",
    val photoVideoStatusEndpoint: String = "",
    val photoVideoResultEndpoint: String = "",
    val photoVideoModel: String = "",
    val photoVideoFormat: String = "JSON",
    val photoVideoImageFormat: String = "base64",
    val photoVideoDuration: String = "5",

    // Save states
    val isCreatePhotoSaved: Boolean = false,
    val isEditPhotoSaved: Boolean = false,
    val isPhotoVideoSaved: Boolean = false,

    // Test states
    val isCreatePhotoTesting: Boolean = false,
    val createPhotoTestResult: String? = null,
    val createPhotoTestError: String? = null,

    val isEditPhotoTesting: Boolean = false,
    val editPhotoTestResult: String? = null,
    val editPhotoTestError: String? = null,

    val isPhotoVideoTesting: Boolean = false,
    val photoVideoTestResult: String? = null,
    val photoVideoTestError: String? = null,
    
    val economyMode: Boolean = true
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val microsoftAuthService: MicrosoftAuthService,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        viewModelScope.launch {
            microsoftAuthService.account.collect { account ->
                _uiState.update { it.copy(microsoftAccount = account) }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val provider = settingsRepository.textProvider.first()
            val url = settingsRepository.baseUrl.first()
            val key = settingsRepository.apiKey.first()
            val path = settingsRepository.textPath.first()
            val model = settingsRepository.model.first()
            val firecrawlKey = settingsRepository.firecrawlApiKey.first()
            
            _uiState.update {
                it.copy(
                    textProvider = provider.takeIf { p -> p.isNotEmpty() } ?: "OpenAI",
                    baseUrl = url,
                    apiKey = key,
                    textPath = path.takeIf { p -> p.isNotEmpty() } ?: "/chat/completions",
                    modelName = model,
                    firecrawlApiKey = firecrawlKey,
                    
                    createPhotoProvider = settingsRepository.createPhotoProvider.first(),
                    createPhotoApiKey = settingsRepository.createPhotoApiKey.first(),
                    createPhotoBaseUrl = settingsRepository.createPhotoBaseUrl.first(),
                    createPhotoEndpoint = settingsRepository.createPhotoEndpoint.first(),
                    createPhotoModel = settingsRepository.createPhotoModel.first(),
                    createPhotoFormat = settingsRepository.createPhotoFormat.first(),
                    
                    editPhotoProvider = settingsRepository.editPhotoProvider.first(),
                    editPhotoApiKey = settingsRepository.editPhotoApiKey.first(),
                    editPhotoBaseUrl = settingsRepository.editPhotoBaseUrl.first(),
                    editPhotoEndpoint = settingsRepository.editPhotoEndpoint.first(),
                    editPhotoModel = settingsRepository.editPhotoModel.first(),
                    editPhotoFormat = settingsRepository.editPhotoFormat.first(),
                    editPhotoImageFormat = settingsRepository.editPhotoImageFormat.first().let { fmt -> if (fmt == "url") "multipart" else fmt },
                    
                    photoVideoProvider = settingsRepository.photoVideoProvider.first(),
                    photoVideoApiKey = settingsRepository.photoVideoApiKey.first(),
                    photoVideoBaseUrl = settingsRepository.photoVideoBaseUrl.first(),
                    photoVideoCreateEndpoint = settingsRepository.photoVideoCreateEndpoint.first(),
                    photoVideoStatusEndpoint = settingsRepository.photoVideoStatusEndpoint.first(),
                    photoVideoResultEndpoint = settingsRepository.photoVideoResultEndpoint.first(),
                    photoVideoModel = settingsRepository.photoVideoModel.first(),
                    photoVideoFormat = settingsRepository.photoVideoFormat.first(),
                    photoVideoImageFormat = settingsRepository.photoVideoImageFormat.first().let { fmt -> if (fmt == "url") "multipart" else fmt },
                    photoVideoDuration = settingsRepository.photoVideoDuration.first(),
                    economyMode = settingsRepository.economyMode.first()
                )
            }
        }
    }

    // Chat Settings Updaters
    fun updateTextProvider(provider: String) { _uiState.update { it.copy(textProvider = provider, isSaved = false) } }
    fun updateBaseUrl(url: String) { _uiState.update { it.copy(baseUrl = url, isSaved = false, validationError = null) } }
    fun updateApiKey(key: String) { _uiState.update { it.copy(apiKey = key, isSaved = false, validationError = null) } }
    fun updateTextPath(path: String) { _uiState.update { it.copy(textPath = path, isSaved = false, validationError = null) } }
    fun updateModelName(model: String) { _uiState.update { it.copy(modelName = model, isSaved = false, validationError = null) } }
    fun updateFirecrawlApiKey(key: String) { _uiState.update { it.copy(firecrawlApiKey = key, isSaved = false) } }

    fun clearTestResult() {
        _uiState.update { it.copy(testResult = null, testError = null, validationError = null) }
    }

    fun applyPreset(presetName: String) {
        val (url, model, path) = when (presetName) {
            "OpenAI" -> Triple("https://api.openai.com/v1", "gpt-4o", "/chat/completions")
            "OpenRouter" -> Triple("https://openrouter.ai/api/v1", "openai/o1-mini", "/chat/completions")
            "xAI" -> Triple("https://api.x.ai/v1", "grok-2-latest", "/chat/completions")
            else -> Triple("", "", "")
        }
        _uiState.update { 
            it.copy(
                baseUrl = url.takeIf { url.isNotEmpty() } ?: it.baseUrl,
                modelName = model.takeIf { model.isNotEmpty() } ?: it.modelName,
                textPath = path.takeIf { path.isNotEmpty() } ?: it.textPath,
                isSaved = false,
                validationError = null
            )
        }
    }

    private fun validateTextSettings(): Boolean {
        val state = _uiState.value
        return when {
            state.baseUrl.isBlank() -> { _uiState.update { it.copy(validationError = "Base URL is required") }; false }
            state.apiKey.isBlank() -> { _uiState.update { it.copy(validationError = "API key is required") }; false }
            state.textPath.isBlank() -> { _uiState.update { it.copy(validationError = "API path is required") }; false }
            state.modelName.isBlank() -> { _uiState.update { it.copy(validationError = "Model is required") }; false }
            else -> true
        }
    }

    fun save() {
        if (!validateTextSettings()) return
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveSettings(state.textProvider, state.apiKey, state.baseUrl, state.textPath, state.modelName, state.firecrawlApiKey)
            _uiState.update { it.copy(isSaved = true, validationError = null, testResult = null, testError = null) }
        }
    }

    fun testConnection() {
        if (!validateTextSettings()) return
        
        _uiState.update { it.copy(isTesting = true, testResult = null, testError = null) }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val baseUrl = state.baseUrl.trimEnd('/')
                val path = if (state.textPath.startsWith("/")) state.textPath else "/${state.textPath}"
                val endpoint = "$baseUrl$path"
                
                val requestBody = ChatRequest(
                    model = state.modelName,
                    messages = listOf(ChatMessage(role = "user", content = "Hello"))
                )

                val requestAdapter = moshi.adapter(ChatRequest::class.java)
                val jsonRequestBody = requestAdapter.toJson(requestBody)
                val body = jsonRequestBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer ${state.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBodyStr = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        _uiState.update { it.copy(isTesting = false, testResult = "Connection successful") }
                    } else {
                        val errorMsg = when (response.code) {
                            401 -> "401 Unauthorized - Check your API key."
                            402 -> "402 Payment Required - No credit or check billing."
                            404 -> "404 Not Found - Wrong Base URL or path."
                            429 -> "429 Rate Limit Exceeded - Sending too many requests."
                            else -> "HTTP ${response.code}: $responseBodyStr"
                        }
                        _uiState.update { it.copy(isTesting = false, testError = errorMsg) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isTesting = false, testError = "Network Error or CORS/proxy: ${e.message}") }
                }
            }
        }
    }

    // Create Photo Updaters
    fun updateCreatePhotoProvider(value: String) { _uiState.update { it.copy(createPhotoProvider = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoApiKey(value: String) { _uiState.update { it.copy(createPhotoApiKey = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoBaseUrl(value: String) { _uiState.update { it.copy(createPhotoBaseUrl = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoEndpoint(value: String) { _uiState.update { it.copy(createPhotoEndpoint = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoModel(value: String) { _uiState.update { it.copy(createPhotoModel = value, isCreatePhotoSaved = false) } }
    fun updateCreatePhotoFormat(value: String) { _uiState.update { it.copy(createPhotoFormat = value, isCreatePhotoSaved = false) } }

    fun saveCreatePhotoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveCreatePhotoSettings(
                state.createPhotoProvider, state.createPhotoApiKey, state.createPhotoBaseUrl,
                state.createPhotoEndpoint, state.createPhotoModel, state.createPhotoFormat
            )
            _uiState.update { it.copy(isCreatePhotoSaved = true) }
        }
    }

    fun testCreatePhotoConnection() {
        val state = _uiState.value
        if (state.createPhotoBaseUrl.isBlank() || state.createPhotoApiKey.isBlank() || state.createPhotoEndpoint.isBlank() || state.createPhotoModel.isBlank()) {
            _uiState.update { it.copy(createPhotoTestError = "Base URL, API Key, Endpoint, and Model are required") }
            return
        }
        _uiState.update { it.copy(isCreatePhotoTesting = true, createPhotoTestResult = null, createPhotoTestError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = executeMediaTest(
                baseUrl = state.createPhotoBaseUrl,
                endpoint = state.createPhotoEndpoint,
                apiKey = state.createPhotoApiKey,
                model = state.createPhotoModel,
                format = state.createPhotoFormat
            )
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    _uiState.update { it.copy(isCreatePhotoTesting = false, createPhotoTestResult = "Connection successful") }
                } else {
                    _uiState.update { it.copy(isCreatePhotoTesting = false, createPhotoTestError = result.exceptionOrNull()?.message ?: "Unknown error") }
                }
            }
        }
    }

    // Edit Photo Updaters
    fun updateEditPhotoProvider(value: String) { _uiState.update { it.copy(editPhotoProvider = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoApiKey(value: String) { _uiState.update { it.copy(editPhotoApiKey = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoBaseUrl(value: String) { _uiState.update { it.copy(editPhotoBaseUrl = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoEndpoint(value: String) { _uiState.update { it.copy(editPhotoEndpoint = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoModel(value: String) { _uiState.update { it.copy(editPhotoModel = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoFormat(value: String) { _uiState.update { it.copy(editPhotoFormat = value, isEditPhotoSaved = false) } }
    fun updateEditPhotoImageFormat(value: String) { _uiState.update { it.copy(editPhotoImageFormat = value, isEditPhotoSaved = false) } }

    fun saveEditPhotoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveEditPhotoSettings(
                state.editPhotoProvider, state.editPhotoApiKey, state.editPhotoBaseUrl,
                state.editPhotoEndpoint, state.editPhotoModel, state.editPhotoFormat, state.editPhotoImageFormat
            )
            _uiState.update { it.copy(isEditPhotoSaved = true) }
        }
    }

    fun testEditPhotoConnection() {
        val state = _uiState.value
        if (state.editPhotoBaseUrl.isBlank() || state.editPhotoApiKey.isBlank() || state.editPhotoEndpoint.isBlank() || state.editPhotoModel.isBlank()) {
            _uiState.update { it.copy(editPhotoTestError = "Base URL, API Key, Endpoint, and Model are required") }
            return
        }
        _uiState.update { it.copy(isEditPhotoTesting = true, editPhotoTestResult = null, editPhotoTestError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = executeMediaTest(
                baseUrl = state.editPhotoBaseUrl,
                endpoint = state.editPhotoEndpoint,
                apiKey = state.editPhotoApiKey,
                model = state.editPhotoModel,
                format = state.editPhotoFormat
            )
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    _uiState.update { it.copy(isEditPhotoTesting = false, editPhotoTestResult = "Connection successful") }
                } else {
                    _uiState.update { it.copy(isEditPhotoTesting = false, editPhotoTestError = result.exceptionOrNull()?.message ?: "Unknown error") }
                }
            }
        }
    }

    // Photo Video Updaters
    fun updatePhotoVideoProvider(value: String) { _uiState.update { it.copy(photoVideoProvider = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoApiKey(value: String) { _uiState.update { it.copy(photoVideoApiKey = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoBaseUrl(value: String) { _uiState.update { it.copy(photoVideoBaseUrl = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoCreateEndpoint(value: String) { _uiState.update { it.copy(photoVideoCreateEndpoint = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoStatusEndpoint(value: String) { _uiState.update { it.copy(photoVideoStatusEndpoint = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoResultEndpoint(value: String) { _uiState.update { it.copy(photoVideoResultEndpoint = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoModel(value: String) { _uiState.update { it.copy(photoVideoModel = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoFormat(value: String) { _uiState.update { it.copy(photoVideoFormat = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoImageFormat(value: String) { _uiState.update { it.copy(photoVideoImageFormat = value, isPhotoVideoSaved = false) } }
    fun updatePhotoVideoDuration(value: String) { _uiState.update { it.copy(photoVideoDuration = value, isPhotoVideoSaved = false) } }

    fun updateEconomyMode(value: Boolean) {
        _uiState.update { it.copy(economyMode = value) }
        viewModelScope.launch {
            settingsRepository.saveEconomyMode(value)
        }
    }

    fun savePhotoVideoSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.savePhotoVideoSettings(
                state.photoVideoProvider, state.photoVideoApiKey, state.photoVideoBaseUrl,
                state.photoVideoCreateEndpoint, state.photoVideoStatusEndpoint, state.photoVideoResultEndpoint,
                state.photoVideoModel, state.photoVideoFormat, state.photoVideoImageFormat, state.photoVideoDuration
            )
            _uiState.update { it.copy(isPhotoVideoSaved = true) }
        }
    }

    fun testPhotoToVideoConnection() {
        val state = _uiState.value
        if (state.photoVideoBaseUrl.isBlank() || state.photoVideoApiKey.isBlank() || state.photoVideoCreateEndpoint.isBlank() || state.photoVideoModel.isBlank()) {
            _uiState.update { it.copy(photoVideoTestError = "Base URL, API Key, create Endpoint, and Model are required") }
            return
        }
        _uiState.update { it.copy(isPhotoVideoTesting = true, photoVideoTestResult = null, photoVideoTestError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = executeMediaTest(
                baseUrl = state.photoVideoBaseUrl,
                endpoint = state.photoVideoCreateEndpoint,
                apiKey = state.photoVideoApiKey,
                model = state.photoVideoModel,
                format = state.photoVideoFormat
            )
            // Ideally we'd test status/result endpoints if the API flow requires them.
            // But checking the createEndpoint is a good baseline measure connection.
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    _uiState.update { it.copy(isPhotoVideoTesting = false, photoVideoTestResult = "Connection successful") }
                } else {
                    _uiState.update { it.copy(isPhotoVideoTesting = false, photoVideoTestError = result.exceptionOrNull()?.message ?: "Unknown error") }
                }
            }
        }
    }

    private suspend fun executeMediaTest(baseUrl: String, endpoint: String, apiKey: String, model: String, format: String): Result<Unit> {
        return try {
            val cleanBaseUrl = baseUrl.trimEnd('/')
            val cleanEndpoint = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
            val url = "$cleanBaseUrl$cleanEndpoint"

            val requestBody = if (format.equals("multipart", ignoreCase = true)) {
                okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart("prompt", "test prompt")
                    // Note: In real multipart usage we might upload an image file
                    .build()
            } else {
                val jsonStr = """{"model":"$model", "prompt":"test prompt"}"""
                jsonStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)

            if (format.equals("JSON", ignoreCase = true)) {
                requestBuilder.addHeader("Content-Type", "application/json")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val responseBodyStr = response.body?.string()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                val errorMsg = when (response.code) {
                    401 -> "401 Unauthorized - Check your API key."
                    402 -> "402 Payment Required - No credit or check billing."
                    404 -> "404 Not Found - Wrong Base URL or path."
                    429 -> "429 Rate Limit Exceeded - Sending too many requests."
                    else -> "HTTP ${response.code}: $responseBodyStr"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            // Include message for other typical errors
            Result.failure(Exception("Network Error or CORS/proxy: ${e.message}"))
        }
    }
    
    fun signInMicrosoft(activity: Activity) {
        viewModelScope.launch {
            microsoftAuthService.acquireTokenInteractive(activity)
        }
    }

    fun signOutMicrosoft() {
        viewModelScope.launch {
            microsoftAuthService.signOut()
        }
    }

    fun resetSaveState() {
        _uiState.update { it.copy(isSaved = false, isCreatePhotoSaved = false, isEditPhotoSaved = false, isPhotoVideoSaved = false) }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val microsoftAuthService: MicrosoftAuthService,
        private val okHttpClient: OkHttpClient,
        private val moshi: Moshi
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(settingsRepository, microsoftAuthService, okHttpClient, moshi) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

