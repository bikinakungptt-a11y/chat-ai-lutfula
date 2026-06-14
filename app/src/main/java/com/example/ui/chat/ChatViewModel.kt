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
    val mode: ChatMode = ChatMode.NORMAL,
    val emailContext: String? = null,
    val suggestedTranslationAction: String? = null
)

class ChatViewModel(
    private val applicationContext: android.content.Context,
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: com.example.data.MemoryRepository,
    private val localStorage: com.example.data.LocalStorage,
    private val microsoftAuthService: com.example.data.MicrosoftAuthService,
    private val microsoftGraphRepository: com.example.data.MicrosoftGraphRepository,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var messageJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            chatRepository.allSessions.collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
                if (sessions.isNotEmpty() && _uiState.value.currentSessionId == null) {
                    selectSession(sessions.first().id)
                }
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
            
            // If the deleted session is the currently active one
            if (_uiState.value.currentSessionId == sessionId) {
                // Find remaining sessions, excluding the deleted one
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

    fun setEmailContext(context: String?) {
        _uiState.update { it.copy(emailContext = context, suggestedTranslationAction = null) }
        
        if (context != null) {
            val languageIdentifier = LanguageIdentification.getClient()
            languageIdentifier.identifyLanguage(context)
                .addOnSuccessListener { languageCode ->
                    if (languageCode != "und" && languageCode != "en") {
                        val languageName = java.util.Locale.forLanguageTag(languageCode).displayLanguage
                        _uiState.update { it.copy(suggestedTranslationAction = "Translate from $languageName to English") }
                    }
                }
        }
    }

    fun clearEmailContext() {
        _uiState.update { it.copy(emailContext = null, suggestedTranslationAction = null) }
    }

    private fun shouldUseRealtimeSearch(messageText: String): Boolean {
        val keywords = listOf(
            "cari", "search", "carikan", "cek", "berita terbaru", "terbaru", 
            "update terbaru", "hari ini", "sekarang", "live", "real time", 
            "realtime", "viral", "trending", "sedang viral", "sosial media", 
            "twitter", "x", "tiktok", "instagram", "youtube", "harga", "price", 
            "kurs", "btc", "bitcoin", "eth", "ethereum", "crypto", "saham", 
            "ihsg", "usd", "idr"
        )
        val textLower = messageText.lowercase()
        return keywords.any { keyword -> 
            Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(textLower)
        }
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
            val lowerText = messageText.lowercase()
            val isOutlookCommand = lowerText.contains("cek inbox outlook") || lowerText.contains("cek email terbaru") || 
                                    lowerText.contains("cari email outlook") || lowerText.contains("cari pdf outlook")
                                    
            if (isOutlookCommand) {
                var sessionId = _uiState.value.currentSessionId
                if (sessionId == null) {
                    val title = "Outlook Query"
                    sessionId = chatRepository.createNewSession(title)
                    _uiState.update { it.copy(currentSessionId = sessionId) }
                    selectSession(sessionId)
                }
                
                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "user", content = messageText))
                
                if (microsoftAuthService.account.value == null) {
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Outlook belum dikonfigurasi. Buka Settings > Microsoft Outlook, isi Client ID, lalu Connect Outlook."))
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                
                _uiState.update { it.copy(loadingText = "Fetching Outlook...") }
                
                if (lowerText.contains("cari email outlook")) {
                    val query = messageText.substringAfter("outlook", "").trim().removePrefix("dari").removePrefix("tentang").trim()
                    microsoftGraphRepository.searchEmails(query)
                } else {
                    microsoftGraphRepository.loadLatestEmails()
                }
                
                val err = microsoftGraphRepository.error.value
                if (err != null) {
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Gagal mengambil data dari Outlook: $err"))
                } else {
                    val emails = microsoftGraphRepository.emails.value
                    if (emails.isEmpty()) {
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Tidak ada email ditemukan dari Outlook."))
                    } else {
                        var result = "Berikut hasil dari Outlook:\n\n"
                        emails.take(5).forEach { email ->
                            val senderName = email.sender?.emailAddress?.name ?: email.sender?.emailAddress?.address ?: "Unknown"
                            result += "- **${email.subject ?: "(No Subject)"}** dari $senderName\n"
                            result += "  _Preview: ${email.bodyPreview?.take(50)}..._\n"
                            if (email.hasAttachments == true) {
                                result += "  *(Ada lampiran)*\n"
                            }
                            if (!email.webLink.isNullOrBlank()) {
                                result += "  [Buka di Browser](${email.webLink})\n"
                            }
                            result += "\n"
                        }
                        if (emails.size > 5) {
                            result += "\n*Dan ${emails.size - 5} email lainnya.*"
                        }
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = result))
                    }
                }
                
                _uiState.update { it.copy(isLoading = false, loadingText = null) }
                return@launch
            }

            try {
                var sessionId = _uiState.value.currentSessionId
                if (sessionId == null) {
                    val title = if (messageText.isNotEmpty()) {
                         if (messageText.length > 20) messageText.substring(0, 20) + "..." else messageText
                    } else "Photo Attached"
                    sessionId = chatRepository.createNewSession(title)
                    _uiState.update { it.copy(currentSessionId = sessionId) }
                    selectSession(sessionId) // to start observing messages for the new session
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
                val prefFirecrawlKey = settingsRepository.firecrawlApiKey.first()
                val aiModels = settingsRepository.savedModelsList.first()
                val supportsVision = aiModels.find { it.modelName == modelName }?.supportsVision ?: false

                val firecrawlKey = if (prefFirecrawlKey.isNotBlank()) prefFirecrawlKey else com.example.BuildConfig.FIRECRAWL_API_KEY
                
                android.util.Log.d("ChatViewModel", "Firecrawl configured: ${firecrawlKey.isNotBlank() && firecrawlKey != "\"YOUR_FIRECRAWL_API_KEY\"" && firecrawlKey != "YOUR_FIRECRAWL_API_KEY"}")
                android.util.Log.d("ChatViewModel", "Firecrawl key length: ${firecrawlKey.length}")

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
                
                var searchContext = ""
                var searchLinks = ""
                
                val urlsInMessage = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(/([\\w- ./?%&=]*)?)?)").findAll(messageText).map { it.value }.toList()
                val useSearch = shouldUseRealtimeSearch(messageText) && urlsInMessage.isEmpty()
                
                if (urlsInMessage.isNotEmpty()) {
                    _uiState.update { it.copy(isLoading = true, loadingText = "Checking website...") }
                    if (firecrawlKey.isBlank() || firecrawlKey == "\"YOUR_FIRECRAWL_API_KEY\"" || firecrawlKey == "YOUR_FIRECRAWL_API_KEY") {
                        searchContext = "The user sent a link, but website reading is not configured. You MUST reply with exactly this text: 'Please add your Firecrawl API key in Settings first.'"
                    } else {
                        val scrapeUrl = urlsInMessage.first()
                        try {
                            val scrapeAdapter = moshi.adapter(com.example.network.FirecrawlScrapeRequest::class.java)
                            val fRequestBody = scrapeAdapter.toJson(com.example.network.FirecrawlScrapeRequest(url = scrapeUrl)).toRequestBody("application/json; charset=utf-8".toMediaType())
                            
                            val fRequest = Request.Builder()
                                .url("https://api.firecrawl.dev/v1/scrape")
                                .addHeader("Authorization", "Bearer $firecrawlKey")
                                .addHeader("Content-Type", "application/json")
                                .post(fRequestBody)
                                .build()
                                
                            val fResponse = okHttpClient.newCall(fRequest).execute()
                            val fResponseStr = fResponse.body?.string()
                            
                            if (fResponse.isSuccessful && fResponseStr != null) {
                                val fResponseAdapter = moshi.adapter(com.example.network.FirecrawlScrapeResponse::class.java)
                                val fResult = fResponseAdapter.fromJson(fResponseStr)
                                
                                val markdown = fResult?.data?.markdown
                                if (!markdown.isNullOrEmpty()) {
                                    val safeMarkdown = if (markdown.length > 10000) markdown.substring(0, 10000) + "...\n[Content Truncated]" else markdown
                                    searchContext = "Use the following scraped web content to answer the user's query:\n\nUrl: $scrapeUrl\nContent:\n$safeMarkdown\n\nInstructions: Answer based on the website content. If the user asked a specific question, answer it. If the user only sent a link without a specific question, summarize what the website is, its main function, and important details. NEVER say you cannot open links or browse websites. You MUST use this extracted text to provide your answer."
                                } else {
                                    searchContext = "I tried to read $scrapeUrl but it returned empty content. Explain to the user it might require login, is blocked, or has no readable text."
                                }
                            } else {
                                val errCode = fResponse.code
                                val errMsg = fResponseStr?.take(200) ?: "Unknown error"
                                searchContext = "I tried to open $scrapeUrl, but it failed. Explain the real reason to the user clearly: HTTP $errCode - $errMsg"
                            }
                        } catch (e: Exception) {
                            searchContext = "I tried to open $scrapeUrl, but Firecrawl returned an error. Explain the real reason to the user: ${e.message}"
                        }
                    }
                    _uiState.update { it.copy(loadingText = null) }
                } else if (useSearch) {
                    if (firecrawlKey.isBlank() || firecrawlKey == "\"YOUR_FIRECRAWL_API_KEY\"" || firecrawlKey == "YOUR_FIRECRAWL_API_KEY") {
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Please add your Firecrawl API key in Settings first. Answering without live search."))
                    } else {
                        try {
                            val searchAdapter = moshi.adapter(com.example.network.FirecrawlSearchRequest::class.java)
                            val firecrawlRequest = com.example.network.FirecrawlSearchRequest(query = messageText)
                            val firecrawlBody = searchAdapter.toJson(firecrawlRequest).toRequestBody("application/json; charset=utf-8".toMediaType())
                            
                            val fRequest = Request.Builder()
                                .url("https://api.firecrawl.dev/v1/search")
                                .addHeader("Authorization", "Bearer $firecrawlKey")
                                .addHeader("Content-Type", "application/json")
                                .post(firecrawlBody)
                                .build()
                                
                            val fResponse = okHttpClient.newCall(fRequest).execute()
                            val fResponseStr = fResponse.body?.string()
                            
                            if (fResponse.isSuccessful && fResponseStr != null) {
                                val fResponseAdapter = moshi.adapter(com.example.network.FirecrawlSearchResponse::class.java)
                                val fResult = fResponseAdapter.fromJson(fResponseStr)
                                
                                val results = fResult?.data
                                if (!results.isNullOrEmpty()) {
                                    val topResults = results.take(3)
                                    searchContext = "Use the following real-time search results to answer the user's query:\n\n" +
                                            topResults.joinToString("\n\n") { "- Title: ${it.title}\n  Description: ${it.description}\n  URL: ${it.url}" }
                                            
                                    searchLinks = "\n\nSources:\n" + topResults.joinToString("\n") { "• ${it.title ?: "Link"}\n  ${it.url}" }
                                }
                            } else {
                                val errCode = fResponse.code
                                val errMsg = fResponseStr ?: "Unknown error"
                                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Real-time search failed (HTTP $errCode): $errMsg"))
                            }
                        } catch (e: Exception) {
                            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Real-time search failed: ${e.message}"))
                        }
                    }
                }

                // Construct full endpoint URL
                val baseUrlCleaned = baseUrl.trimEnd('/')
                val pathCleaned = if (path.startsWith("/")) path else "/$path"
                val endpoint = "$baseUrlCleaned$pathCleaned"

                // Prepare system prompt based on mode
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
                
                if (memoryEnabled) {
                    val allMemories = memoryRepository.getAllMemories()
                    val queryWords = messageText.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }
                    
                    val scoredMemories = allMemories.map { mem ->
                        val memLower = mem.content.lowercase()
                        val score = queryWords.count { memLower.contains(it) }
                        mem to score
                    }.sortedByDescending { it.second }
                    
                    val relevantMemories = if (scoredMemories.any { it.second > 0 }) {
                         scoredMemories.filter { it.second > 0 }.take(10).map { it.first }
                    } else {
                         allMemories.take(5)
                    }

                    if (relevantMemories.isNotEmpty()) {
                        systemPrompt += "\n\nUser memory:\n" + relevantMemories.joinToString("\n") { "- ${it.content}" } + 
                                        "\nUse these memories only when relevant. Do not mention memory unless the user asks."
                    }
                }
                
                if (searchContext.isNotEmpty()) {
                    systemPrompt += "\n\n$searchContext"
                }

                val emailContext = _uiState.value.emailContext
                if (emailContext != null) {
                    systemPrompt += "\n\nThe user requested to use the following email as context:\n$emailContext"
                }

                val chatMessages = mutableListOf<com.example.network.ChatRequestMessage>()
                chatMessages.add(com.example.network.ChatRequestMessage(role = "system", content = listOf(com.example.network.VisionContent(type = "text", text = systemPrompt))))
                
                // Track if image or file sending failed
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
                                if (isNew) {
                                    attachmentSendFailedMsg = "Gagal memproses/mengirim gambar. Harap periksa izin akses atau gambar tidak valid."
                                }
                                parts.add(com.example.network.VisionContent(type = "text", text = content)) // fallback to text only for old messages if permission lost
                            }
                        } else {
                            var fileText: String? = null
                            try {
                                applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                                    val size = stream.available()
                                    if (size < 5 * 1024 * 1024) { // Max 5MB for text extraction inline
                                        fileText = stream.bufferedReader().readText()
                                    } else {
                                        fileText = "File terlalu besar untuk dibaca langsung."
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("ChatViewModel", "Error reading file", e)
                            }
                            
                            if (fileText != null) {
                                if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("csv")) {
                                    parts.add(com.example.network.VisionContent(type = "text", text = "$content\n\n[Attached File Content]:\n$fileText"))
                                } else {
                                    if (isNew) {
                                        attachmentSendFailedMsg = "Model/API ini belum mendukung membaca file secara langsung (hanya teks/gambar)."
                                    }
                                    parts.add(com.example.network.VisionContent(type = "text", text = "$content\n\n[File Attached but type '$mimeType' cannot be parsed locally]"))
                                }
                            } else {
                                if (isNew) {
                                    attachmentSendFailedMsg = "Gagal membaca konten file."
                                }
                                parts.add(com.example.network.VisionContent(type = "text", text = content))
                            }
                        }
                    } else {
                        parts.add(com.example.network.VisionContent(type = "text", text = content))
                    }
                    com.example.network.ChatRequestMessage(role = role, content = parts)
                }
                
                // Map existing messages
                previousMessagesSnapshot.filter { !it.content.startsWith("⚠️") }.forEach {
                    chatMessages.add(makeMessage(it.role, it.content, it.imageUri, false))
                }
                
                val localInstruction = localStorage.getInstruction()
                if (localInstruction.isNotEmpty()) {
                    chatMessages.add(com.example.network.ChatRequestMessage(role = "system", content = listOf(com.example.network.VisionContent(type = "text", text = "CRITICAL USER PREFERENCE (ALWAYS FOLLOW THIS IN YOUR NEXT RESPONSE):\n$localInstruction"))))
                }

                // Manually append the latest user message
                chatMessages.add(makeMessage("user", messageText, imageUri, true))

                if (attachmentSendFailedMsg != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = attachmentSendFailedMsg
                        )
                    }
                    return@launch
                }
                
                if (hasAnyImage && !supportsVision) {
                   chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Model ini tidak mendukung membaca gambar. Pilih model vision."))
                   _uiState.update { it.copy(isLoading = false, error = "Model ini tidak mendukung membaca gambar. Pilih model vision.") }
                   return@launch
                }

                val enableReasoningParameter = true // Settings flag

                val reasoning = if (enableReasoningParameter) {
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
                        val finalReply = if (searchLinks.isNotEmpty() && !assistantReply.contains("No content received.")) {
                            assistantReply + searchLinks
                        } else {
                            assistantReply
                        }
                        
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = finalReply))
                        
                        _uiState.update {
                            it.copy(isLoading = false)
                        }
                    }
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "401 Unauthorized - Check your API key. Some providers require a specific format."
                        402 -> "402 Payment Required - Check your provider's billing account."
                        404 -> "404 Not Found - Invalid Base URL or Endpoint."
                        429 -> "429 Rate Limit Exceeded - You are sending too many requests."
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
                var mimeType = resolver.getType(uri) ?: "image/jpeg"
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
        private val microsoftAuthService: com.example.data.MicrosoftAuthService,
        private val microsoftGraphRepository: com.example.data.MicrosoftGraphRepository,
        private val okHttpClient: OkHttpClient,
        private val moshi: Moshi
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                return ChatViewModel(applicationContext, settingsRepository, chatRepository, memoryRepository, localStorage, microsoftAuthService, microsoftGraphRepository, okHttpClient, moshi) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
