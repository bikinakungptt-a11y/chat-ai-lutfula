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
    val isLoading: Boolean = false,
    val loadingText: String? = null,
    val error: String? = null,
    val mode: ChatMode = ChatMode.NORMAL,
    val emailContext: String? = null,
    val suggestedTranslationAction: String? = null
)

class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: com.example.data.MemoryRepository,
    private val localStorage: com.example.data.LocalStorage,
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
                val firecrawlKey = com.example.BuildConfig.FIRECRAWL_API_KEY
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
                        searchContext = "The user sent a link, but website reading is not configured. You MUST reply with exactly this text: 'Website reading is not configured yet. Please add Firecrawl API key in environment variables.'"
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
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Real-time search API key is missing. Answering without live search."))
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

                val chatMessages = mutableListOf<ChatMessage>()
                chatMessages.add(ChatMessage(role = "system", content = systemPrompt))
                
                // Map existing messages (excluding the warnings if any) from the snapshot
                chatMessages.addAll(previousMessagesSnapshot.filter { !it.content.startsWith("⚠️") }.map { 
                    ChatMessage(role = it.role, content = it.content) 
                })
                
                val localInstruction = localStorage.getInstruction()
                if (localInstruction.isNotEmpty()) {
                    chatMessages.add(ChatMessage(role = "system", content = "CRITICAL USER PREFERENCE (ALWAYS FOLLOW THIS IN YOUR NEXT RESPONSE):\n$localInstruction"))
                }

                // Manually append the latest user message
                chatMessages.add(ChatMessage(role = "user", content = messageText))

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

    class Factory(
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
                return ChatViewModel(settingsRepository, chatRepository, memoryRepository, localStorage, okHttpClient, moshi) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
