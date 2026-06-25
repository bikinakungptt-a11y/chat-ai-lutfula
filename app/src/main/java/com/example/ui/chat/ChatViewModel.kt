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

    private val _uiState = MutableStateFlow(
        ChatUiState(
            mode = try {
                ChatMode.valueOf(localStorage.getChatMode())
            } catch (e: Exception) {
                ChatMode.NORMAL
            }
        )
    )
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
                        state.copy(
                            messages = messages.map {
                                UiMessage(
                                    id = it.id.toString(),
                                    role = it.role,
                                    content = it.content,
                                    imageUri = it.imageUri
                                )
                            }
                        )
                    } else {
                        state
                    }
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

    private fun getRealtimeSearchQuery(messageText: String): String? {
        val text = messageText.trim()
        if (text.isEmpty()) return null

        val parts = text.split(Regex("\\s+"), limit = 2)
        val firstWord = parts.firstOrNull()?.lowercase() ?: return null
        val triggers = setOf("#berita", "#browser", "#cari")

        return if (firstWord in triggers) {
            parts.getOrNull(1)?.trim() ?: ""
        } else {
            null
        }
    }

    private data class RealtimeSearchData(
        val context: String,
        val links: String
    )

    private fun cleanBuildConfigKey(value: String): String {
        val key = value.trim().trim('"')
        return if (key.isBlank() || key.startsWith("YOUR_", ignoreCase = true)) "" else key
    }

    private fun getFirecrawlApiKey(): String {
        return cleanBuildConfigKey(com.example.BuildConfig.FIRECRAWL_API_KEY)
    }

    private fun parseSearchData(jsonText: String, sourceName: String, query: String): RealtimeSearchData? {
        val json = org.json.JSONObject(jsonText)
        val dataArray = json.optJSONArray("data") ?: return null
        if (dataArray.length() == 0) return null

        val topResults = mutableListOf<String>()
        val sourcesList = mutableListOf<String>()
        val maxItems = minOf(5, dataArray.length())

        for (i in 0 until maxItems) {
            val item = dataArray.optJSONObject(i) ?: continue
            val title = item.optString("title", "No Title").trim()
            val description = item.optString("description", "").trim()
            val url = item.optString("url", "").trim()
            val markdown = item.optString("markdown", "").trim()
            val content = when {
                description.isNotBlank() -> description
                markdown.isNotBlank() -> markdown.take(700)
                else -> "Tidak ada deskripsi."
            }

            topResults.add(
                "${i + 1}. Title: $title\n" +
                    "   Summary: $content\n" +
                    "   URL: $url"
            )
            if (url.isNotBlank()) {
                sourcesList.add("${i + 1}. $title\n$url")
            }
        }

        if (topResults.isEmpty()) return null

        val context = """
            REALTIME_SEARCH_RESULTS_FROM_$sourceName
            Query: $query

            Results:
            ${topResults.joinToString("\n\n")}

            Instructions for the assistant:
            - Jawab dalam Bahasa Indonesia.
            - Berikan informasi berita/informasi terbaru yang diminta user secara ringkas, jelas, dan langsung.
            - Gunakan hanya data realtime di atas sebagai dasar jawaban.
            - Jangan mengarang detail yang tidak ada di hasil realtime.
            - Jika hasil belum cukup, katakan bahwa data realtime belum cukup lengkap.
            - Jika cocok, sertakan poin utama dan sumber singkat.
        """.trimIndent()

        val links = if (sourcesList.isNotEmpty()) {
            "\n\nSumber:\n" + sourcesList.joinToString("\n")
        } else {
            ""
        }

        return RealtimeSearchData(context, links)
    }

    private fun runFirecrawlSearch(query: String): Result<RealtimeSearchData> {
        val firecrawlKey = getFirecrawlApiKey()
        if (firecrawlKey.isBlank()) {
            return Result.failure(Exception("FIRECRAWL_API_KEY belum tersedia di BuildConfig/.env aplikasi Android."))
        }

        return try {
            val jsonBody = org.json.JSONObject()
                .put("query", query)
                .put("limit", 5)
                .toString()

            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://api.firecrawl.dev/v1/search")
                .addHeader("Authorization", "Bearer $firecrawlKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseText = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Result.failure(Exception("Firecrawl HTTP ${response.code}: ${responseText.take(300)}"))
            } else {
                val parsed = parseSearchData(responseText, "FIRECRAWL", query)
                if (parsed != null) {
                    Result.success(parsed)
                } else {
                    Result.failure(Exception("Firecrawl tidak mengembalikan hasil relevan."))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Firecrawl gagal: ${e.message}"))
        }
    }

    private fun runVercelSearchFallback(query: String): Result<RealtimeSearchData> {
        return try {
            val queryUrlEncoded = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/search?q=$queryUrlEncoded")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseText = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Result.failure(Exception("Vercel search HTTP ${response.code}: ${responseText.take(300)}"))
            } else {
                val parsed = parseSearchData(responseText, "VERCEL_SEARCH", query)
                if (parsed != null) {
                    Result.success(parsed)
                } else {
                    Result.failure(Exception("Vercel search tidak mengembalikan hasil relevan."))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Vercel search gagal: ${e.message}"))
        }
    }

    private fun runRealtimeSearch(query: String): Result<RealtimeSearchData> {
        val firecrawlResult = runFirecrawlSearch(query)
        if (firecrawlResult.isSuccess) return firecrawlResult

        val fallbackResult = runVercelSearchFallback(query)
        if (fallbackResult.isSuccess) return fallbackResult

        val firecrawlError = firecrawlResult.exceptionOrNull()?.message ?: "unknown"
        val fallbackError = fallbackResult.exceptionOrNull()?.message ?: "unknown"
        return Result.failure(Exception("Firecrawl gagal: $firecrawlError\nFallback Vercel gagal: $fallbackError"))
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
                val selectedModel = aiModels.find { it.modelName == modelName }
                val supportsVision = selectedModel?.supportsVision ?: false
                val supportsReasoning = selectedModel?.supportsReasoning ?: false
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

                var searchContext = ""
                var searchLinks = ""
                val textLower = messageText.lowercase()
                val searchQuery = getRealtimeSearchQuery(messageText)
                val useSearch = searchQuery != null

                if (useSearch && searchQuery!!.isEmpty()) {
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Masukkan kata kunci setelah #berita, #browser, atau #cari."))
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                    return@launch
                }

                val urlsInMessage = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(/([\\w- ./?%&=]*)?)?)").findAll(messageText).map { it.value }.toList()

                if (useSearch) {
                    _uiState.update { it.copy(loadingText = "Searching Firecrawl...") }
                    val result = runRealtimeSearch(searchQuery!!)
                    if (result.isSuccess) {
                        val data = result.getOrThrow()
                        searchContext = data.context
                        searchLinks = data.links
                    } else {
                        val errMsg = result.exceptionOrNull()?.message ?: "unknown error"
                        chatRepository.insertMessage(
                            MessageEntity(
                                sessionId = sessionId,
                                role = "assistant",
                                content = "Search Firecrawl gagal, jadi saya belum bisa memastikan data terbaru.\n\n$errMsg"
                            )
                        )
                        _uiState.update { it.copy(isLoading = false, loadingText = null) }
                        return@launch
                    }
                } else if (urlsInMessage.isNotEmpty()) {
                    _uiState.update { it.copy(loadingText = "Checking website...") }
                    val scrapeUrl = urlsInMessage.first()
                    try {
                        val jsonBody = org.json.JSONObject().put("url", scrapeUrl).toString()
                        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                        val request = Request.Builder()
                            .url("https://chat-ai-lutfula.vercel.app/api/read-url")
                            .post(requestBody)
                            .build()

                        val response = okHttpClient.newCall(request).execute()
                        val responseText = response.body?.string().orEmpty()

                        searchContext = if (response.isSuccessful && responseText.isNotBlank()) {
                            val safeText = responseText.take(10000)
                            "Use the following scraped web content to answer the user's query:\n\nUrl: $scrapeUrl\nContent:\n$safeText\n\nInstructions: Answer based on the website content."
                        } else {
                            "I tried to open $scrapeUrl, tapi gagal. HTTP ${response.code} - ${responseText.take(200)}"
                        }
                    } catch (e: Exception) {
                        searchContext = "I tried to open $scrapeUrl, tapi terjadi error: ${e.message}"
                    }
                }

                val isCryptoQuery = Regex("\\b(btc|bitcoin|eth|ethereum|sol|solana|bnb|xrp|ripple|doge|dogecoin|usdt|tether)\\b").containsMatchIn(textLower)
                val isNewsOrSentiment = Regex("\\b(berita|news|sentimen|kenapa|positif|negatif|turun|naik)\\b").containsMatchIn(textLower)
                if (!useSearch && isCryptoQuery && !isNewsOrSentiment && urlsInMessage.isEmpty()) {
                    try {
                        val cryptoIds = mutableListOf<String>()
                        if (Regex("\\b(btc|bitcoin)\\b").containsMatchIn(textLower)) cryptoIds.add("bitcoin")
                        if (Regex("\\b(eth|ethereum)\\b").containsMatchIn(textLower)) cryptoIds.add("ethereum")
                        if (Regex("\\b(sol|solana)\\b").containsMatchIn(textLower)) cryptoIds.add("solana")
                        if (Regex("\\b(bnb|binancecoin)\\b").containsMatchIn(textLower)) cryptoIds.add("binancecoin")
                        if (Regex("\\b(xrp|ripple)\\b").containsMatchIn(textLower)) cryptoIds.add("ripple")
                        if (Regex("\\b(doge|dogecoin)\\b").containsMatchIn(textLower)) cryptoIds.add("dogecoin")
                        if (Regex("\\b(usdt|tether)\\b").containsMatchIn(textLower)) cryptoIds.add("tether")
                        val cryptoData = cryptoPriceRepository.getCryptoPrice(cryptoIds.distinct())
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = cryptoData))
                        _uiState.update { it.copy(isLoading = false, loadingText = null) }
                        return@launch
                    } catch (e: Exception) {
                        searchContext += "\nRealtime crypto API failed: ${e.message}\n"
                    }
                }

                val isHolidayQuery = Regex("\\b(tanggal merah|libur|working day|hari libur|suro|muharram|kalender)\\b").containsMatchIn(textLower)
                if (!useSearch && isHolidayQuery) {
                    try {
                        val cal = java.util.Calendar.getInstance()
                        cal.timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
                        if (textLower.contains("besok")) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        if (textLower.contains("lusa")) cal.add(java.util.Calendar.DAY_OF_YEAR, 2)
                        if (textLower.contains("kemarin")) cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                        val dateRegex = Regex("""(\d{4})-(\d{2})-(\d{2})""")
                        val match = dateRegex.find(textLower)
                        val targetDate = if (match != null) {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd")
                            sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
                            sdf.parse(match.value) ?: cal.time
                        } else {
                            cal.time
                        }
                        val holidayInfo = holidayRepository.isWorkingDay(targetDate)
                        searchContext += "\nHoliday API Result:\n$holidayInfo\nInstruction: Use this result to answer holiday/tanggal merah questions.\n"
                    } catch (e: Exception) {
                        searchContext += "\nHoliday API Check Failed: ${e.message}\n"
                    }
                }

                val baseUrlCleaned = baseUrl.trimEnd('/')
                val pathCleaned = if (path.startsWith("/")) path else "/$path"
                val endpoint = "$baseUrlCleaned$pathCleaned"
                val mode = _uiState.value.mode

                var systemPrompt = when (mode) {
                    ChatMode.NORMAL -> "You are a helpful AI assistant. Provide fast, simple, and direct answers."
                    ChatMode.THINK -> "You are a helpful AI assistant. Approach tasks with careful reasoning and thorough checking. Keep the final answer clear."
                    ChatMode.THINK_DEEPLY -> "You are a helpful AI assistant. Provide deeper analysis for complex tasks, but keep news answers concise unless the user asks for detail."
                }

                if (langPref == "id") {
                    systemPrompt += "\n\nAlways respond in Bahasa Indonesia. Use clear, simple Indonesian unless the user asks for another language."
                }

                systemPrompt += "\n\n" + AppGuide.TEXT

                val antiHallucination = """
                    ATURAN PENTING REALTIME:
                    Kamu tidak boleh mengklaim sudah mencari di internet kecuali ada REALTIME_SEARCH_RESULTS di prompt.
                    Jika realtime search gagal, katakan jujur data realtime belum tersedia.
                    Untuk hasil #berita, #browser, atau #cari, jawab ringkas, jelas, dan berdasarkan sumber yang diberikan.
                """.trimIndent()
                systemPrompt += "\n\n$antiHallucination"

                if (memoryEnabled) {
                    val allMemories = memoryRepository.getAllMemories()
                    if (allMemories.isNotEmpty()) {
                        val queryWords = messageText.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }
                        val relevantMemories = allMemories
                            .map { mem -> mem to queryWords.count { mem.content.lowercase().contains(it) } }
                            .sortedByDescending { it.second }
                            .filter { it.second > 0 }
                            .take(10)
                            .map { it.first }
                            .ifEmpty { allMemories.take(5) }

                        if (relevantMemories.isNotEmpty()) {
                            systemPrompt += "\n\nUser memory:\n" + relevantMemories.joinToString("\n") { "- ${it.content}" } +
                                "\nUse these memories only when relevant. Do not mention memory unless the user asks."
                        }
                    }
                }

                if (searchContext.isNotEmpty()) {
                    systemPrompt += "\n\n$searchContext"
                }

                val timeContext = getCurrentTimeContext()
                systemPrompt += "\n\n$timeContext"

                val chatMessages = mutableListOf<com.example.network.ChatRequestMessage>()
                chatMessages.add(
                    com.example.network.ChatRequestMessage(
                        role = "system",
                        content = listOf(com.example.network.VisionContent(type = "text", text = systemPrompt))
                    )
                )

                var attachmentSendFailedMsg: String? = null
                var hasAnyImage = false

                val makeMessage = { role: String, content: String, attachmentUriStr: String?, isNew: Boolean ->
                    val parts = mutableListOf<com.example.network.VisionContent>()
                    if (!attachmentUriStr.isNullOrEmpty()) {
                        val uri = android.net.Uri.parse(attachmentUriStr)
                        val mimeType = applicationContext.contentResolver.getType(uri) ?: ""
                        if (mimeType.startsWith("image/")) {
                            hasAnyImage = true
                            val b64 = uriToBase64(attachmentUriStr)
                            if (b64 != null) {
                                parts.add(com.example.network.VisionContent(type = "text", text = content.ifEmpty { "Please check this image." }))
                                parts.add(com.example.network.VisionContent(type = "image_url", imageUrl = com.example.network.VisionImageUrl(url = b64)))
                            } else {
                                if (isNew) attachmentSendFailedMsg = "Gagal memproses/mengirim gambar. Harap periksa izin akses atau gambar tidak valid."
                                parts.add(com.example.network.VisionContent(type = "text", text = content))
                            }
                        } else {
                            var fileText: String? = null
                            try {
                                applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                                    val size = stream.available()
                                    fileText = if (size < 5 * 1024 * 1024) stream.bufferedReader().readText() else "File terlalu besar untuk dibaca langsung."
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ChatViewModel", "Error reading file", e)
                            }

                            if (fileText != null && (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("csv"))) {
                                parts.add(com.example.network.VisionContent(type = "text", text = "$content\n\n[Attached File Content]:\n$fileText"))
                            } else {
                                if (isNew) attachmentSendFailedMsg = "Model/API ini belum mendukung membaca file secara langsung selain teks/gambar."
                                parts.add(com.example.network.VisionContent(type = "text", text = content))
                            }
                        }
                    } else {
                        parts.add(com.example.network.VisionContent(type = "text", text = content))
                    }
                    com.example.network.ChatRequestMessage(role = role, content = parts)
                }

                previousMessagesSnapshot.filter { !it.content.startsWith("⚠️") }.forEach {
                    chatMessages.add(makeMessage(it.role, it.content, it.imageUri, false))
                }

                val localInstruction = localStorage.getInstruction()
                if (localInstruction.isNotEmpty()) {
                    chatMessages.add(
                        com.example.network.ChatRequestMessage(
                            role = "system",
                            content = listOf(com.example.network.VisionContent(type = "text", text = "CRITICAL USER PREFERENCE (ALWAYS FOLLOW THIS IN YOUR NEXT RESPONSE):\n$localInstruction"))
                        )
                    )
                }

                val finalUserMessage = "$timeContext\n\nPERTANYAAN USER:\n$messageText"
                chatMessages.add(makeMessage("user", finalUserMessage, imageUri, true))

                if (attachmentSendFailedMsg != null) {
                    _uiState.update {
                        it.copy(isLoading = false, loadingText = null, error = attachmentSendFailedMsg)
                    }
                    return@launch
                }

                if (hasAnyImage && !supportsVision) {
                    val msg = "⚠️ Model ini tidak mendukung membaca gambar. Pilih model vision."
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = msg))
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = msg) }
                    return@launch
                }

                val reasoning = if (supportsReasoning) {
                    when (mode) {
                        ChatMode.THINK -> ReasoningConfig("medium")
                        ChatMode.THINK_DEEPLY -> ReasoningConfig("high")
                        ChatMode.NORMAL -> null
                    }
                } else {
                    null
                }

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
                            it.copy(isLoading = false, loadingText = null, error = "API Error: ${chatResponse.error.message}")
                        }
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
                        503 -> "HTTP 503: Provider/model sedang unavailable. Coba mode Normal atau model lain."
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
            } else {
                null
            }
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
