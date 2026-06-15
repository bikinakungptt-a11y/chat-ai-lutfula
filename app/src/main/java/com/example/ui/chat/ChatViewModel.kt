package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppGuide
import com.example.data.ChatRepository
import com.example.data.ChatSessionEntity
import com.example.data.MessageEntity
import com.example.data.SettingsRepository
import com.example.network.ChatRequest
import com.example.network.ChatRequestMessage
import com.example.network.ChatResponse
import com.example.network.ReasoningConfig
import com.example.network.VisionContent
import com.example.network.VisionImageUrl
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
    private val toolRouter = ChatToolRouter(okHttpClient, cryptoPriceRepository, holidayRepository)

    private val _uiState = MutableStateFlow(ChatUiState())
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
    }

    private suspend fun handleMemoryCommand(messageText: String, sessionId: Long): Boolean {
        val textLower = messageText.trim().lowercase()
        val memoryEnabled = settingsRepository.memoryEnabled.first()

        if (textLower == "memory off") {
            settingsRepository.saveMemoryEnabled(false)
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is now disabled."))
            return true
        }
        if (textLower == "memory on") {
            settingsRepository.saveMemoryEnabled(true)
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is now enabled."))
            return true
        }

        val isMemoryCommand = textLower.startsWith("ingat") || textLower.startsWith("simpan") ||
            textLower.startsWith("remember") || textLower == "hapus memory" ||
            textLower == "lihat memory" || textLower.startsWith("lupakan")

        if (!memoryEnabled && isMemoryCommand) {
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Memory is disabled. Say 'memory on' to enable it."))
            return true
        }

        if (textLower == "hapus memory") {
            memoryRepository.deleteAllMemories()
            localStorage.prefs.edit().remove("custom_instruction").commit()
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "All memories and instructions have been deleted."))
            return true
        }

        if (textLower == "lihat memory" || textLower == "debug lokal") {
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

        val savePrefixes = listOf("ingat:", "ingat ini:", "simpan dimemory anda:", "remember:")
        for (prefix in savePrefixes) {
            if (textLower.startsWith(prefix)) {
                val content = messageText.substring(prefix.length).trim()
                return saveMemoryIfSafe(content, sessionId, true)
            }
        }

        val deletePrefixes = listOf("lupakan:")
        for (prefix in deletePrefixes) {
            if (textLower.startsWith(prefix)) {
                val content = messageText.substring(prefix.length).trim()
                memoryRepository.deleteMemoryByContent(content)
                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "I have forgotten that."))
                return true
            }
        }

        return false
    }

    private suspend fun saveMemoryIfSafe(content: String, sessionId: Long, isExplicit: Boolean): Boolean {
        val lower = content.lowercase()
        val criticalSecrets = listOf("password", "api key", "apikey", "token", "secret", "address", "alamat", "phone", "telepon", "bank", "payment", "credit card")

        if (criticalSecrets.any { lower.contains(it) }) {
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ I am not allowed to remember sensitive data like API keys, passwords, addresses, and banking info."))
            return true
        }

        if (!isExplicit) {
            val familyWords = listOf("mama", "ibu", "mother", "ayah", "bapak", "father", "sibling", "siblings", "wife", "husband", "child", "children")
            if (familyWords.any { lower.contains(it) }) {
                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ I am not allowed to automatically save family identity information."))
                return true
            }
        }

        val saved = localStorage.saveInstruction(content)
        if (saved) {
            memoryRepository.insertMemory(content = content, category = if (isExplicit) "manual" else "auto")
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Got it! I will remember this."))
        } else {
            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Failed to save memory to localStorage."))
        }
        return true
    }

    fun sendMessage(userText: String, imageUri: String? = null) {
        val messageText = userText.trim()
        if (messageText.isEmpty() && imageUri == null) return

        val previousMessagesSnapshot = _uiState.value.messages.toList()

        _uiState.update {
            it.copy(isLoading = true, loadingText = null, error = null)
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var sessionId = _uiState.value.currentSessionId
                if (sessionId == null) {
                    val title = if (messageText.isNotEmpty()) {
                        if (messageText.length > 20) messageText.substring(0, 20) + "..." else messageText
                    } else {
                        "Photo Attached"
                    }
                    sessionId = chatRepository.createNewSession(title)
                    _uiState.update { it.copy(currentSessionId = sessionId) }
                    selectSession(sessionId)
                }

                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "user", content = messageText, imageUri = imageUri))

                if (handleMemoryCommand(messageText, sessionId)) {
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                    return@launch
                }

                val apiKey = settingsRepository.apiKey.first()
                val baseUrl = settingsRepository.baseUrl.first()
                val path = settingsRepository.textPath.first()
                val modelName = settingsRepository.model.first()
                val aiModels = settingsRepository.savedModelsList.first()
                val supportsVision = aiModels.find { it.modelName == modelName }?.supportsVision ?: false
                val langPref = settingsRepository.assistantLanguagePreference.first()
                val memoryEnabled = settingsRepository.memoryEnabled.first()

                if (apiKey.isBlank() || baseUrl.isBlank() || modelName.isBlank()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loadingText = null,
                            error = "Missing configuration. Please check your API Key, Base URL, and Model Name in Settings."
                        )
                    }
                    return@launch
                }

                _uiState.update { it.copy(loadingText = "Checking backend tools...") }
                val toolResult = toolRouter.route(messageText)
                val searchContext = if (toolResult.usedTool) toolResult.context else ""
                val searchLinks = if (toolResult.usedTool) toolResult.sources else ""
                _uiState.update { it.copy(loadingText = null) }

                val baseUrlCleaned = baseUrl.trimEnd('/')
                val pathCleaned = if (path.startsWith("/")) path else "/$path"
                val endpoint = "$baseUrlCleaned$pathCleaned"

                val mode = _uiState.value.mode
                var systemPrompt = when (mode) {
                    ChatMode.NORMAL -> "You are a helpful AI assistant. Provide fast, simple, and direct answers."
                    ChatMode.THINK -> "You are a helpful AI assistant. Approach tasks with careful reasoning and thorough checking. Explain your answer clearly, but do not reveal hidden private chain-of-thought."
                    ChatMode.THINK_DEEPLY -> "You are a helpful AI assistant. Provide deeper analysis, detailed debugging, and careful step-by-step explanations. Do not reveal hidden private chain-of-thought."
                }

                if (langPref == "id") {
                    systemPrompt += "\n\nAlways respond in Bahasa Indonesia. Use clear, simple Indonesian unless the user asks for another language."
                }

                systemPrompt += "\n\n" + AppGuide.TEXT

                val antiHallucination = """
                    ATURAN PENTING (ANTI-HALUSINASI TOOL):
                    Kamu tidak boleh mengklaim telah menjalankan date, membuka browser, browsing, mengecek website, mengecek API, membaca halaman, atau mencari di internet kecuali aplikasi benar-benar mengirimkan hasil dari backend/tool ke prompt ini.
                    Jika tool/backend tidak jalan, kamu harus bilang jujur: "Data realtime belum tersedia." atau "Backend realtime gagal, jadi saya tidak bisa memastikan."
                    Gunakan data realtime dari backend jika tersedia. Jangan mengarang harga, berita, tanggal merah, atau isi website.
                    Jawaban default harus ringkas, jelas, dan langsung. Jangan terlalu panjang kecuali user meminta detail.
                """.trimIndent()
                systemPrompt += "\n\n$antiHallucination"

                if (memoryEnabled) {
                    val allMemories = memoryRepository.getAllMemories()
                    val queryWords = messageText.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }
                    val relevantMemories = allMemories
                        .map { mem -> mem to queryWords.count { mem.content.lowercase().contains(it) } }
                        .sortedByDescending { it.second }
                        .let { scored ->
                            if (scored.any { it.second > 0 }) scored.filter { it.second > 0 }.take(10).map { it.first } else allMemories.take(5)
                        }

                    if (relevantMemories.isNotEmpty()) {
                        systemPrompt += "\n\nUser memory:\n" + relevantMemories.joinToString("\n") { "- ${it.content}" } +
                            "\nUse these memories only when relevant. Do not mention memory unless the user asks."
                    }
                }

                if (searchContext.isNotEmpty()) {
                    systemPrompt += "\n\nREALTIME_BACKEND_TOOL_CONTEXT:\n$searchContext"
                }

                val timeContext = getCurrentTimeContext()
                systemPrompt += "\n\n$timeContext"

                val chatMessages = mutableListOf<ChatRequestMessage>()
                chatMessages.add(ChatRequestMessage(role = "system", content = listOf(VisionContent(type = "text", text = systemPrompt))))

                var attachmentSendFailedMsg: String? = null
                var hasAnyImage = false

                fun makeMessage(role: String, content: String, attachmentUriStr: String?, isNew: Boolean): ChatRequestMessage {
                    val parts = mutableListOf<VisionContent>()
                    if (!attachmentUriStr.isNullOrEmpty()) {
                        val uri = android.net.Uri.parse(attachmentUriStr)
                        val mimeType = applicationContext.contentResolver.getType(uri) ?: ""

                        if (mimeType.startsWith("image/")) {
                            hasAnyImage = true
                            val b64 = uriToBase64(attachmentUriStr)
                            if (b64 != null) {
                                parts.add(VisionContent(type = "text", text = content.ifEmpty { "Please check this image." }))
                                parts.add(VisionContent(type = "image_url", imageUrl = VisionImageUrl(url = b64)))
                            } else {
                                if (isNew) attachmentSendFailedMsg = "Gagal memproses/mengirim gambar. Harap periksa izin akses atau gambar tidak valid."
                                parts.add(VisionContent(type = "text", text = content))
                            }
                        } else {
                            val canReadAsText = mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("csv")
                            if (canReadAsText) {
                                try {
                                    val fileText = applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                                        stream.bufferedReader().readText().take(20000)
                                    }
                                    parts.add(VisionContent(type = "text", text = "$content\n\n[Attached File Content]:\n${fileText.orEmpty()}"))
                                } catch (e: Exception) {
                                    if (isNew) attachmentSendFailedMsg = "Gagal membaca konten file."
                                    parts.add(VisionContent(type = "text", text = content))
                                }
                            } else {
                                if (isNew) attachmentSendFailedMsg = "Model/API ini belum mendukung membaca file secara langsung (hanya teks/gambar)."
                                parts.add(VisionContent(type = "text", text = "$content\n\n[File Attached but type '$mimeType' cannot be parsed locally]"))
                            }
                        }
                    } else {
                        parts.add(VisionContent(type = "text", text = content))
                    }
                    return ChatRequestMessage(role = role, content = parts)
                }

                previousMessagesSnapshot.filter { !it.content.startsWith("⚠️") }.forEach {
                    chatMessages.add(makeMessage(it.role, it.content, it.imageUri, false))
                }

                val localInstruction = localStorage.getInstruction()
                if (localInstruction.isNotEmpty()) {
                    chatMessages.add(
                        ChatRequestMessage(
                            role = "system",
                            content = listOf(VisionContent(type = "text", text = "CRITICAL USER PREFERENCE (ALWAYS FOLLOW THIS IN YOUR NEXT RESPONSE):\n$localInstruction"))
                        )
                    )
                }

                val finalUserMessage = "$timeContext\n\nPERTANYAAN USER:\n$messageText"
                chatMessages.add(makeMessage("user", finalUserMessage, imageUri, true))

                if (attachmentSendFailedMsg != null) {
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = attachmentSendFailedMsg) }
                    return@launch
                }

                if (hasAnyImage && !supportsVision) {
                    val err = "⚠️ Model ini tidak mendukung membaca gambar. Pilih model vision."
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = err))
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = err) }
                    return@launch
                }

                val reasoning = when (mode) {
                    ChatMode.THINK -> ReasoningConfig("medium")
                    ChatMode.THINK_DEEPLY -> ReasoningConfig("high")
                    ChatMode.NORMAL -> null
                }

                val requestBody = ChatRequest(model = modelName, messages = chatMessages, reasoning = reasoning)
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
                        _uiState.update { it.copy(isLoading = false, loadingText = null, error = "API Error: ${chatResponse.error.message}") }
                    } else {
                        val assistantReply = chatResponse?.choices?.firstOrNull()?.message?.content ?: "No content received."
                        val finalReply = if (searchLinks.isNotEmpty() && !assistantReply.contains("No content received.")) {
                            assistantReply + searchLinks
                        } else {
                            assistantReply
                        }
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = finalReply))
                        _uiState.update { it.copy(isLoading = false, loadingText = null) }
                    }
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "401 Unauthorized - Check your API key. Some providers require a specific format."
                        402 -> "402 Payment Required - Check your provider's billing account."
                        404 -> "404 Not Found - Invalid Base URL or Endpoint."
                        429 -> "429 Rate Limit Exceeded - You are sending too many requests."
                        else -> "HTTP ${response.code}: $responseBodyStr"
                    }
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = errorMsg) }
                }
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingText = null,
                        error = "Network Error or Timeout: ${e.message}. Check your internet connection and Base URL."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingText = null,
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
    val isPastMaghrib = now.hour >= 18

    return """
CURRENT_REAL_TIME_CONTEXT:
Tanggal sekarang: $dateStr
Jam sekarang: $timeStr
Timezone: ${zoneId.id}
Country code: ID
Sudah lewat Maghrib fallback: $isPastMaghrib

Aturan Suro/Muharram:
1. Kalender Hijriah/Jawa berganti setelah Maghrib, bukan jam 00:00.
2. Jika besok adalah 1 Muharram / 1 Suro dan jam sekarang >= 18:00 (Sudah lewat Maghrib), jawab:
   "Ya, sekarang sudah masuk malam 1 Suro / malam 1 Muharram."
3. Bedakan:
   - malam 1 Suro = mulai setelah Maghrib tanggal sebelumnya
   - tanggal merah resmi = tanggal Masehi besoknya
4. Jangan jawab "belum Suro" hanya karena tanggal Masehi masih tanggal sebelumnya.
""".trimIndent()
}
