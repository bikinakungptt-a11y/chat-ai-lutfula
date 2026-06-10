package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import com.example.network.ChatMessage
import com.example.network.ChatRequest
import com.example.network.ChatResponse
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
    val error: String? = null,
    val mode: ChatMode = ChatMode.NORMAL,
    val emailContext: String? = null,
    val suggestedTranslationAction: String? = null
)

class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
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

    fun clearChat() {
        viewModelScope.launch(Dispatchers.IO) {
            val sessionId = _uiState.value.currentSessionId
            if (sessionId != null) {
                chatRepository.deleteSession(sessionId)
            }
            _uiState.update { it.copy(error = null, currentSessionId = null, messages = emptyList()) }
        }
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

    fun sendMessage(userText: String, imageUri: String? = null) {
        val messageText = userText.trim()
        if (messageText.isEmpty() && imageUri == null) return

        _uiState.update { 
            it.copy(
                isLoading = true,
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
                
                val apiKey = settingsRepository.apiKey.first()
                val baseUrl = settingsRepository.baseUrl.first()
                val path = settingsRepository.textPath.first()
                val modelName = settingsRepository.model.first()
                val firecrawlKey = settingsRepository.firecrawlApiKey.first()
                val langPref = settingsRepository.assistantLanguagePreference.first()

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
                
                val useSearch = shouldUseRealtimeSearch(messageText)
                
                if (useSearch) {
                    if (firecrawlKey.isBlank()) {
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
                                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Real-time search failed, answering without live search."))
                            }
                        } catch (e: Exception) {
                            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "⚠️ Real-time search failed, answering without live search."))
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
                
                if (searchContext.isNotEmpty()) {
                    systemPrompt += "\n\n$searchContext"
                }

                val emailContext = _uiState.value.emailContext
                if (emailContext != null) {
                    systemPrompt += "\n\nThe user requested to use the following email as context:\n$emailContext"
                }

                val chatMessages = mutableListOf<ChatMessage>()
                chatMessages.add(ChatMessage(role = "system", content = systemPrompt))
                
                // Map existing messages (excluding the warnings if any)
                chatMessages.addAll(_uiState.value.messages.filter { !it.content.startsWith("⚠️") }.map { 
                    ChatMessage(role = it.role, content = it.content) 
                })

                val requestBody = ChatRequest(
                    model = modelName,
                    messages = chatMessages
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
        private val okHttpClient: OkHttpClient,
        private val moshi: Moshi
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                return ChatViewModel(settingsRepository, chatRepository, okHttpClient, moshi) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
