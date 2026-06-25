package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import com.example.network.ChatMessage
import com.example.network.ChatRequest
import com.example.network.ChatResponse
import com.example.network.ReasoningConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import com.example.data.AppGuide
import com.example.data.ChatSessionEntity
import com.example.data.ChatRepository
import com.example.data.MessageEntity
import kotlinx.coroutines.flow.map
import com.google.mlkit.nl.languageid.LanguageIdentification

enum class ChatMode {
    NORMAL, THINK, THINK_DEEPLY
}

data class UiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val imageUri: String? = null,
    val isError: Boolean = false
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val sessions: List<ChatSessionEntity> = emptyList(),
    val currentSessionId: Long? = null,
    val currentModel: String = "",
    val savedModelsList: List<com.example.network.AiModelConfig> = emptyList(),
    val isLoading: Boolean = false,
    val loadingText: String? = null,
    val error: String? = null,
    val mode: ChatMode = ChatMode.NORMAL
)

class ChatViewModel(
    private val applicationContext: android.content.Context,
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: com.example.data.MemoryRepository,
    private val localStorage: com.example.data.LocalStorage,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : ViewModel() {

    private val cryptoPriceRepository = com.example.data.CryptoPriceRepository(okHttpClient)
    private val holidayRepository = com.example.data.HolidayRepository(okHttpClient)

    private val _uiState = MutableStateFlow(ChatUiState(mode = try { ChatMode.valueOf(localStorage.getChatMode()) } catch (e: Exception) { ChatMode.NORMAL }))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messageJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            chatRepository.allSessions.collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            settingsRepository.model.collect { model ->
                _uiState.update { it.copy(currentModel = model) }
            }
        }
        viewModelScope.launch {
            settingsRepository.savedModelsList.collect { models ->
                _uiState.update { it.copy(savedModelsList = models) }
                val current = _uiState.value.currentModel
                if (current.isNotBlank() && models.isNotEmpty() && !models.any { it.modelName == current }) {
                    updateSelectedModel(models.first().modelName)
                } else if (current.isNotBlank() && models.isEmpty()) {
                    updateSelectedModel("")
                }
            }
        }
    }

    fun updateSelectedModel(modelName: String) {
        viewModelScope.launch {
            settingsRepository.updateModel(modelName)
        }
    }

    fun selectSession(sessionId: Long) {
        _uiState.update { it.copy(currentSessionId = sessionId, messages = emptyList()) }
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            chatRepository.getMessagesForSession(sessionId).collect { messages ->
                _uiState.update { state ->
                    if (state.currentSessionId == sessionId) {
                         state.copy(messages = messages.map { UiMessage(it.id.toString(), it.role, it.content, it.imageUri) })
                    } else state
                }
            }
        }
    }
    
    fun createNewSession() {
        _uiState.update { it.copy(currentSessionId = null, messages = emptyList()) }
        messageJob?.cancel()
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.deleteSession(sessionId)
            
            if (_uiState.value.currentSessionId == sessionId) {
                val remainingSessions = _uiState.value.sessions.filter { it.id != sessionId }
                if (remainingSessions.isNotEmpty()) {
                    selectSession(remainingSessions.first().id)
                } else {
                    createNewSession()
                }
            }
        }
    }

    fun setMode(mode: ChatMode) {
        _uiState.update { it.copy(mode = mode) }
        localStorage.saveChatMode(mode.name)
    }

    private suspend fun handleMemoryCommand(messageText: String, sessionId: Long): Boolean {
        val textLower = messageText.trim().lowercase()
        val memoryEnabled = settingsRepository.memoryEnabled.first()
        
        if (textLower == "memory off") {
            settingsRepository.saveMemoryEnabled(false)
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is now disabled."))
            return true
        } else if (textLower == "memory on") {
            settingsRepository.saveMemoryEnabled(true)
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is now enabled."))
            return true
        }

        if (!memoryEnabled && (textLower.startsWith("ingat") || textLower.startsWith("simpan") || textLower.startsWith("remember") || textLower == "hapus memory" || textLower == "lihat memory" || textLower.startsWith("lupakan"))) {
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is disabled. Say 'memory on' to enable it."))
            return true
        }

        if (textLower == "hapus memory") {
            memoryRepository.deleteAllMemories()
            localStorage.prefs.edit().remove("custom_instruction").commit()
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "All memories and instructions have been deleted."))
            return true
        } else if (textLower == "lihat memory" || textLower == "debug lokal") {
            val memories = memoryRepository.getAllMemories()
            val savedLocal = localStorage.getInstruction()
            if (memories.isEmpty() && savedLocal.isEmpty()) {
                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is empty."))
            } else {
                val listStr = memories.joinToString("\n") { "- ${it.content}" }
                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Debug Check - LocalStorage:\n$savedLocal\n\nRoom memories:\n$listStr"))
            }
            return true
        }

        return false
    }

    fun sendMessage(userText: String, imageUri: String? = null) {
        val messageText = userText.trim()
        if (messageText.isEmpty() && imageUri == null) return

        val previousMessagesSnapshot = _uiState.value.messages.toList()

        _uiState.update { 
            it.copy(
                isLoading = true,
                loadingText = null,
                error = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var sessionId = _uiState.value.currentSessionId
                if (sessionId == null) {
                    val title = if (messageText.isNotEmpty()) {
                         if (messageText.length > 20) messageText.substring(0, 20) + "..." else messageText
                    } else "Photo Attached"
                    sessionId = chatRepository.createNewSession(title)
                    _uiState.update { it.copy(currentSessionId = sessionId) }
                    selectSession(sessionId)
                }
                
                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "user", content = messageText, imageUri = imageUri))
                
                if (handleMemoryCommand(messageText, sessionId)) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                
                val apiKey = settingsRepository.apiKey.first()
                val baseUrl = settingsRepository.baseUrl.first()
                val path = settingsRepository.textPath.first()
                val modelName = settingsRepository.model.first()
                val aiModels = settingsRepository.savedModelsList.first()
                val supportsVision = aiModels.find { it.modelName == modelName }?.supportsVision ?: false
                val supportsReasoning = aiModels.find { it.modelName == modelName }?.supportsReasoning ?: false

                val langPref = settingsRepository.assistantLanguagePreference.first()
                val memoryEnabled = settingsRepository.memoryEnabled.first()

                if (apiKey.isBlank() || baseUrl.isBlank() || modelName.isBlank()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Missing configuration. Please check your API Key, Base URL, and Model Name in Settings."
                        )
                    }
                    return@launch
                }

                val baseUrlCleaned = baseUrl.trimEnd('/')
                val pathCleaned = if (path.startsWith("/")) path else "/$path"
                val endpoint = "$baseUrlCleaned$pathCleaned"

                val mode = _uiState.value.mode
                var systemPrompt = when (mode) {
                    ChatMode.NORMAL -> "You are a helpful AI assistant. Provide fast, simple, and direct answers."
                    ChatMode.THINK -> "You are a helpful AI assistant. Approach tasks with careful reasoning and thorough checking. Explain your thought process."
                    ChatMode.THINK_DEEPLY -> "You are a helpful AI assistant. Provide deeper analysis, detailed debugging, and exhaustive step-by-step reasoning. You are better for coding and complex tasks."
                }
                
                if (langPref == "id") {
                    systemPrompt += "\n\nAlways respond in Bahasa Indonesia. Use clear, simple Indonesian unless the user asks for another language."
                }
                
                systemPrompt += "\n\n" + AppGuide.TEXT
                systemPrompt += "\n\n" + getCurrentTimeContext()
                
                if (memoryEnabled) {
                    val allMemories = memoryRepository.getAllMemories()
                    if (allMemories.isNotEmpty()) {
                        systemPrompt += "\n\nUser memory:\n" + allMemories.take(5).joinToString("\n") { "- ${it.content}" } +
                            "\nUse these memories only when relevant. Do not mention memory unless the user asks."
                    }
                }

                val chatMessages = mutableListOf<com.example.network.ChatRequestMessage>()
                chatMessages.add(com.example.network.ChatRequestMessage(role = "system", content = listOf(com.example.network.VisionContent(type = "text", text = systemPrompt))))

                previousMessagesSnapshot.filter { !it.content.startsWith("⚠️") }.forEach {
                    chatMessages.add(com.example.network.ChatRequestMessage(role = it.role, content = listOf(com.example.network.VisionContent(type = "text", text = it.content))))
                }
                
                val localInstruction = localStorage.getInstruction()
                if (localInstruction.isNotEmpty()) {
                    chatMessages.add(com.example.network.ChatRequestMessage(role = "system", content = listOf(com.example.network.VisionContent(type = "text", text = "CRITICAL USER PREFERENCE (ALWAYS FOLLOW THIS IN YOUR NEXT RESPONSE):\n$localInstruction"))))
                }

                val userParts = mutableListOf<com.example.network.VisionContent>()
                userParts.add(com.example.network.VisionContent(type = "text", text = "$messageText\n\n${getCurrentTimeContext()}"))
                if (imageUri != null) {
                    val b64 = uriToBase64(imageUri)
                    if (b64 != null) {
                        userParts.add(com.example.network.VisionContent(type = "image_url", imageUrl = com.example.network.VisionImageUrl(url = b64)))
                    }
                }
                chatMessages.add(com.example.network.ChatRequestMessage(role = "user", content = userParts))

                if (imageUri != null && !supportsVision) {
                   chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Model ini tidak mendukung membaca gambar. Pilih model vision."))
                   _uiState.update { it.copy(isLoading = false, error = "Model ini tidak mendukung membaca gambar. Pilih model vision.") }
                   return@launch
                }

                val reasoning = if (supportsReasoning) {
                    when (mode) {
                        ChatMode.THINK -> ReasoningConfig("medium")
                        ChatMode.THINK_DEEPLY -> ReasoningConfig("high")
                        ChatMode.NORMAL -> null
                    }
                } else null

                val requestBody = ChatRequest(
                    model = modelName,
                    messages = chatMessages,
                    reasoning = reasoning
                )

                val requestAdapter = moshi.adapter(ChatRequest::class.java)
                val jsonRequestBody = requestAdapter.toJson(requestBody)
                val body = jsonRequestBody.toRequestBody("application/json; charset=utf-8".toMediaType())

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBodyStr = response.body?.string()

                if (response.isSuccessful && responseBodyStr != null) {
                    val responseAdapter = moshi.adapter(ChatResponse::class.java)
                    val chatResponse = responseAdapter.fromJson(responseBodyStr)
                    
                    if (chatResponse?.error != null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "API Error: ${chatResponse.error.message}"
                            )
                        }
                    } else {
                        val assistantReply = chatResponse?.choices?.firstOrNull()?.message?.content ?: "No content received."
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = assistantReply))
                        _uiState.update { it.copy(isLoading = false) }
                    }
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "401 Unauthorized - Check your API key. Some providers require a specific format."
                        402 -> "402 Payment Required - Check your provider's billing account."
                        404 -> "404 Not Found - Invalid Base URL or Endpoint."
                        429 -> "429 Rate Limit Exceeded - You are sending too many requests."
                        503 -> "HTTP 503: Provider/model sedang unavailable. Coba mode Normal atau model lain."
                        else -> "HTTP ${response.code}: $responseBodyStr"
                    }
                    _uiState.update {
                        it.copy(isLoading = false, error = errorMsg)
                    }
                }

            } catch (e: IOException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Network Error or Timeout: ${e.message}. Check your internet connection and Base URL."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "An unexpected error occurred: ${e.message}"
                    )
                }
            }
        }
    }

    private fun uriToBase64(uriStr: String?): String? {
        if (uriStr.isNullOrEmpty()) return null
        return try {
            val uri = android.net.Uri.parse(uriStr)
            val resolver = applicationContext.contentResolver
            val inputStream = resolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            if (bytes != null) {
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP)
                val mimeType = resolver.getType(uri) ?: "image/jpeg"
                "data:$mimeType;base64,$base64"
            } else null
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Error converting image to base64", e)
            null
        }
    }

    class Factory(
        private val applicationContext: android.content.Context,
        private val settingsRepository: SettingsRepository,
        private val chatRepository: ChatRepository,
        private val memoryRepository: com.example.data.MemoryRepository,
        private val localStorage: com.example.data.LocalStorage,
        private val okHttpClient: OkHttpClient,
        private val moshi: Moshi
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                return ChatViewModel(applicationContext, settingsRepository, chatRepository, memoryRepository, localStorage, okHttpClient, moshi) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

fun getCurrentTimeContext(): String {
    val zoneId = try {
        java.time.ZoneId.systemDefault()
    } catch (e: Exception) {
        java.time.ZoneId.of("Asia/Jakarta")
    }

    val now = java.time.ZonedDateTime.now(zoneId)
    val dateFormatter = java.time.format.DateTimeFormatter.ofPattern(
        "EEEE, dd MMMM yyyy",
        java.util.Locale.forLanguageTag("id-ID")
    )
    val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    
    val dateStr = now.format(dateFormatter)
    val timeStr = now.format(timeFormatter)

    return """
CURRENT_REAL_TIME_CONTEXT:
Tanggal sekarang: $dateStr
Jam sekarang: $timeStr
Timezone: ${zoneId.id}
Country code: ID
""".trimIndent()
}
