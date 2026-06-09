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

enum class ChatMode {
    NORMAL, THINK, THINK_DEEPLY
}

data class UiMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val isError: Boolean = false
)

data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val mode: ChatMode = ChatMode.NORMAL
)

class ChatViewModel(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun setMode(mode: ChatMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList(), error = null) }
    }

    fun sendMessage(userText: String) {
        val messageText = userText.trim()
        if (messageText.isEmpty()) return

        val userMessage = UiMessage(role = "user", content = messageText)
        _uiState.update { 
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apiKey = settingsRepository.apiKey.first()
                val baseUrl = settingsRepository.baseUrl.first()
                val modelName = settingsRepository.model.first()
                val firecrawlKey = settingsRepository.firecrawlApiKey.first()

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
                
                if (firecrawlKey.isBlank()) {
                    _uiState.update {
                        val warningMessage = UiMessage(role = "assistant", content = "⚠️ Real-time search API key is missing. Answering without live search.")
                        it.copy(messages = it.messages + warningMessage)
                    }
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
                            _uiState.update {
                                val warningMessage = UiMessage(role = "assistant", content = "⚠️ Real-time search failed, answering without live search.")
                                it.copy(messages = it.messages + warningMessage)
                            }
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            val warningMessage = UiMessage(role = "assistant", content = "⚠️ Real-time search failed, answering without live search.")
                            it.copy(messages = it.messages + warningMessage)
                        }
                    }
                }

                // Construct full endpoint URL
                val endpoint = if (baseUrl.endsWith("/")) {
                    "${baseUrl}chat/completions"
                } else {
                    "${baseUrl}/chat/completions"
                }

                // Prepare system prompt based on mode
                val mode = _uiState.value.mode
                var systemPrompt = when (mode) {
                    ChatMode.NORMAL -> "You are a helpful AI assistant. Provide short and direct answers without unnecessary verbosity."
                    ChatMode.THINK -> "You are a helpful AI assistant. Provide more detailed reasoning-style answers. Explain your thought process."
                    ChatMode.THINK_DEEPLY -> "You are a helpful AI assistant. Provide exhaustive, deeper, step-by-step structured answers. Analyze all aspects thoroughly."
                }
                
                if (searchContext.isNotEmpty()) {
                    systemPrompt += "\n\n$searchContext"
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
                        val assistantMessage = UiMessage(role = "assistant", content = finalReply)
                        _uiState.update {
                            it.copy(
                                messages = it.messages + assistantMessage,
                                isLoading = false
                            )
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
        private val okHttpClient: OkHttpClient,
        private val moshi: Moshi
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                return ChatViewModel(settingsRepository, okHttpClient, moshi) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
