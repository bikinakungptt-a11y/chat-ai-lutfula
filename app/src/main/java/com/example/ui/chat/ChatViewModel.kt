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
        localStorage.saveChatMode(mode.name)
    }

    private fun getRealtimeSearchQuery(messageText: String): String? {
        val text = messageText.trim()
        val triggers = listOf("#berita", "#browser", "#cari")
        for (trigger in triggers) {
            if (text.lowercase().startsWith(trigger)) {
                return text.substring(trigger.length).trim()
            }
        }
        return null
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
                val aiModels = settingsRepository.savedModelsList.first()
                val supportsVision = aiModels.find { it.modelName == modelName }?.supportsVision ?: false

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
                
                val textLower = messageText.lowercase()
                val isGoldQuery = Regex("\\b(xau|gold|emas)\\b").containsMatchIn(textLower)
                val isFiatQuery = Regex("\\b(usd|idr|eur|gbp|jpy|kurs|mata\\s*uang)\\b").containsMatchIn(textLower)
                
                var priceApiSuccess = false
                var priceApiData = ""
                var priceApiError = ""
                
                if (isGoldQuery) {
                    _uiState.update { it.copy(isLoading = true, loadingText = "Fetching Metals API...") }
                    var metalsKey = com.example.BuildConfig.METALS_API_KEY
                    if (metalsKey.isBlank() || metalsKey == "YOUR_METALS_API_KEY") {
                        metalsKey = com.example.BuildConfig.METALS_DEV_API_KEY
                    }

                    if (metalsKey.isBlank() || metalsKey == "YOUR_METALS_DEV_API_KEY" || metalsKey == "YOUR_METALS_API_KEY") {
                        val errMsg = "API harga realtime gagal: METALS_API_KEY/METALS_DEV_API_KEY not configured."
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = errMsg))
                        _uiState.update { it.copy(isLoading = false, loadingText = null) }
                        return@launch
                    } else {
                        try {
                            val request = okhttp3.Request.Builder()
                                .url("https://api.metals.dev/v1/latest?api_key=$metalsKey&currency=USD&unit=toz")
                                .build()
                            val response = okHttpClient.newCall(request).execute()
                            val body = response.body?.string()
                            if (response.isSuccessful && body != null) {
                                val json = org.json.JSONObject(body)
                                val metals = json.optJSONObject("metals") ?: json.optJSONObject("rates")?.optJSONObject("metals") ?: json.optJSONObject("rates")
                                val rates = json.optJSONObject("rates")
                                var price = 0.0
                                if (metals != null && metals.has("gold")) {
                                    price = metals.optDouble("gold")
                                } else if (metals != null && metals.has("XAU")) {
                                    price = metals.optDouble("XAU")
                                } else if (rates != null && rates.has("gold")) {
                                    price = rates.optDouble("gold")
                                } else if (rates != null && rates.has("XAU")) {
                                    price = rates.optDouble("XAU")
                                }
                                
                                val priceFormatted = if (price > 0) String.format("%.2f", price) else "N/A"
                                val finalStr = "Harga XAU realtime:\n1 XAU = $$priceFormatted\nSumber: metals.dev realtime API"
                                
                                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = finalStr))
                                _uiState.update { it.copy(isLoading = false, loadingText = null) }
                                return@launch
                            } else {
                                val errMsg = "API harga realtime gagal: Metals API ${response.code}\nResponse: $body"
                                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = errMsg))
                                _uiState.update { it.copy(isLoading = false, loadingText = null) }
                                return@launch
                            }
                        } catch(e: Exception) {
                            val errMsg = "API harga realtime gagal: Metals API ${e.message}"
                            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = errMsg))
                            _uiState.update { it.copy(isLoading = false, loadingText = null) }
                            return@launch
                        }
                    }
                }
                
                if (isFiatQuery) {
                    _uiState.update { it.copy(isLoading = true, loadingText = "Fetching Currency API...") }
                    try {
                        val request = okhttp3.Request.Builder()
                            .url("https://api.frankfurter.app/latest?from=USD")
                            .build()
                        val response = okHttpClient.newCall(request).execute()
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            priceApiData += "Frankfurter (Base USD): $body\n"
                            priceApiSuccess = true
                        } else {
                            priceApiError += "API harga realtime gagal: Frankfurter API ${response.code}\n"
                        }
                    } catch(e: Exception) {
                        priceApiError += "API harga realtime gagal: Frankfurter API ${e.message}\n"
                    }
                }
                
                val urlsInMessage = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(/([\\w- ./?%&=]*)?)?)").findAll(messageText).map { it.value }.toList()
                val cryptoIds = mutableListOf<String>()
                if (Regex("\\b(btc|bitcoin)\\b").containsMatchIn(textLower)) cryptoIds.add("bitcoin")
                if (Regex("\\b(eth|ethereum)\\b").containsMatchIn(textLower)) cryptoIds.add("ethereum")
                if (Regex("\\b(sol|solana)\\b").containsMatchIn(textLower)) cryptoIds.add("solana")
                if (Regex("\\b(bnb|binancecoin)\\b").containsMatchIn(textLower)) cryptoIds.add("binancecoin")
                if (Regex("\\b(xrp|ripple)\\b").containsMatchIn(textLower)) cryptoIds.add("ripple")
                if (Regex("\\b(doge|dogecoin)\\b").containsMatchIn(textLower)) cryptoIds.add("dogecoin")
                if (Regex("\\b(usdt|tether)\\b").containsMatchIn(textLower)) cryptoIds.add("tether")
                
                var isCryptoQuery = cryptoIds.isNotEmpty()
                val isNewsOrSentiment = Regex("\\b(berita|news|sentimen|kenapa|positif|negatif|turun|naik)\\b").containsMatchIn(textLower)
                val searchQuery = getRealtimeSearchQuery(messageText)
                var useSearch = searchQuery != null
                if (useSearch && searchQuery!!.isEmpty()) {
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Masukkan kata kunci setelah #berita, #browser, atau #cari."))
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                
                if (isCryptoQuery) {
                    _uiState.update { it.copy(isLoading = true, loadingText = "Fetching CoinGecko API...") }
                    try {
                        val cryptoData = cryptoPriceRepository.getCryptoPrice(cryptoIds)
                        if (!isNewsOrSentiment && urlsInMessage.isEmpty()) {
                            // Direct response
                            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = cryptoData))
                            _uiState.update { it.copy(isLoading = false, loadingText = null) }
                            return@launch
                        } else {
                            priceApiData += "$cryptoData\n"
                            priceApiSuccess = true
                        }
                    } catch(e: Exception) {
                        priceApiError += "API harga CoinGecko gagal: ${e.message}\n"
                    }
                }
                
                val isHolidayQuery = Regex("\\b(tanggal merah|libur|working day|hari libur|suro|muharram|kalender)\\b").containsMatchIn(textLower)
                if (isHolidayQuery) {
                    _uiState.update { it.copy(isLoading = true, loadingText = "Checking Holidays...") }
                    try {
                        // Use a simple date parsing or just assume today/tomorrow based on keywords
                        val cal = java.util.Calendar.getInstance()
                        cal.timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta")
                        if (textLower.contains("besok")) {
                            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                        } else if (textLower.contains("lusa")) {
                            cal.add(java.util.Calendar.DAY_OF_YEAR, 2)
                        } else if (textLower.contains("kemarin")) {
                            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                        }
                        // try to extract YYYY-MM-DD
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
                        searchContext += "Holiday API Result for the requested date:\n$holidayInfo\n\nInstruction: Use the Holiday API result to answer if it is a holiday/tanggal merah and the reason.\n\n"
                        useSearch = false
                    } catch (e: Exception) {
                        searchContext += "Holiday API Check Failed: ${e.message}\n\n"
                    }
                }
                
                if (priceApiData.isNotEmpty() || priceApiError.isNotEmpty()) {
                    searchContext += "Realtime Price API Data:\n"
                    if (priceApiData.isNotEmpty()) searchContext += priceApiData + "\n"
                    if (priceApiError.isNotEmpty()) searchContext += priceApiError + "\n"
                    searchContext += "Instruction: Use the Realtime Price API Data above. DO NOT guess or use search for these prices. " +
                        (if (priceApiError.isNotEmpty()) "Show the exact 'API harga realtime gagal' error message to the user. " else "") + "\n\n"
                }

                useSearch = useSearch && urlsInMessage.isEmpty()
                
                if (priceApiSuccess && !isNewsOrSentiment && !Regex("\\b(saham|ihsg)\\b").containsMatchIn(textLower)) {
                    useSearch = false
                }
                
                if (urlsInMessage.isNotEmpty()) {
                    _uiState.update { it.copy(isLoading = true, loadingText = "Checking website...") }
                    val scrapeUrl = urlsInMessage.first()
                    try {
                        val jsonBody = org.json.JSONObject().put("url", scrapeUrl).toString()
                        val fRequestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                        
                        val fRequest = Request.Builder()
                            .url("https://chat-ai-lutfula.vercel.app/api/read-url")
                            .post(fRequestBody)
                            .build()
                            
                        val fResponse = okHttpClient.newCall(fRequest).execute()
                        val fResponseStr = fResponse.body?.string()
                        
                        if (fResponse.isSuccessful && fResponseStr != null) {
                            try {
                                val jsonResponse = org.json.JSONObject(fResponseStr)
                                // Handle if it has data.markdown like Firecrawl or just markdown
                                val markdown = jsonResponse.optJSONObject("data")?.optString("markdown") ?: jsonResponse.optString("markdown", fResponseStr)
                                
                                if (markdown.isNotEmpty()) {
                                    val safeMarkdown = if (markdown.length > 10000) markdown.substring(0, 10000) + "...\n[Content Truncated]" else markdown
                                    searchContext = "Use the following scraped web content to answer the user's query:\n\nUrl: $scrapeUrl\nContent:\n$safeMarkdown\n\nInstructions: Answer based on the website content. If the user asked a specific question, answer it. If the user only sent a link without a specific question, summarize what the website is, its main function, and important details. NEVER say you cannot open links or browse websites. You MUST use this extracted text to provide your answer."
                                } else {
                                    searchContext = "I tried to read $scrapeUrl but it returned empty. Explain to the user."
                                }
                            } catch (e: Exception) {
                                // If response is not JSON, just pass it as text
                                val safeMarkdown = if (fResponseStr.length > 10000) fResponseStr.substring(0, 10000) + "...\n[Content Truncated]" else fResponseStr
                                searchContext = "Use the following scraped web content to answer the user's query:\n\nUrl: $scrapeUrl\nContent:\n$safeMarkdown\n\nInstructions: Answer based on the website content."
                            }
                        } else {
                            val errCode = fResponse.code
                            val errMsg = fResponseStr?.take(200) ?: "Unknown error"
                            searchContext = "I tried to open $scrapeUrl, tapi gagal. HTTP $errCode - $errMsg"
                        }
                    } catch (e: Exception) {
                        searchContext = "I tried to open $scrapeUrl, tapi terjadi error: ${e.message}"
                    }
                    _uiState.update { it.copy(loadingText = null) }
                } else if (useSearch) {
                    try {
                        val queryUrlEncoded = java.net.URLEncoder.encode(searchQuery ?: messageText, "UTF-8")
                        val fRequest = Request.Builder()
                            .url("https://chat-ai-lutfula.vercel.app/api/search?q=$queryUrlEncoded")
                            .get()
                            .build()
                            
                        val fResponse = okHttpClient.newCall(fRequest).execute()
                        val fResponseStr = fResponse.body?.string()
                        
                        if (fResponse.isSuccessful && fResponseStr != null) {
                            try {
                                val jsonResponse = org.json.JSONObject(fResponseStr)
                                val dataArray = jsonResponse.optJSONArray("data")
                                
                                if (dataArray != null && dataArray.length() > 0) {
                                    val topResults = mutableListOf<String>()
                                    val sourcesList = mutableListOf<String>()
                                    val maxItems = minOf(3, dataArray.length())
                                    
                                    for (i in 0 until maxItems) {
                                        val item = dataArray.optJSONObject(i)
                                        if (item != null) {
                                            val title = item.optString("title", "No Title")
                                            val description = item.optString("description", "")
                                            val url = item.optString("url", "")
                                            topResults.add("- Title: $title\n  Description: $description\n  URL: $url")
                                            sourcesList.add("• $title\n  $url")
                                        }
                                    }
                                    searchContext = "Use the following real-time search results to answer the user's query:\n\n" + topResults.joinToString("\n\n")
                                    searchLinks = "\n\nSources:\n" + sourcesList.joinToString("\n")
                                } else {
                                    searchContext = "Search berjalan tetapi tidak ada hasil relevan. Jangan mengarang."
                                }
                            } catch (e: Exception) {
                                searchContext = "Search results format error: ${e.message}"
                            }
                        } else {
                            val errCode = fResponse.code
                            val errMsg = fResponseStr ?: "Unknown error"
                            searchContext = "Search gagal (HTTP $errCode - $errMsg). Katakan jujur."
                        }
                    } catch (e: Exception) {
                        searchContext = "Search gagal (${e.message}). Katakan jujur."
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
                
                val antiHallucination = """
                    
                    ATURAN PENTING (ANTI-HALUSINASI TOOL):
                    Kamu tidak boleh mengklaim telah:
                    - menjalankan date
                    - membuka browser
                    - browsing
                    - mengecek website
                    - mengecek API
                    - membaca halaman
                    - mencari di internet
                    Kecuali aplikasi benar-benar mengirimkan hasil dari tool/API tersebut ke prompt ini.
                    Jika tool/API tidak jalan, kamu harus bilang jujur: "Data realtime belum tersedia." atau "Search gagal, jadi saya tidak bisa memastikan."
                    Jawaban default harus ringkas, jelas, dan langsung. Jangan terlalu panjang kecuali user meminta detail.
                """.trimIndent()
                systemPrompt += "\n\n$antiHallucination"
                
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

                val timeContext = getCurrentTimeContext()
                systemPrompt += "\n\n" + timeContext
                android.util.Log.d("ChatViewModel", "CurrentTimeContext injected: true")
                android.util.Log.d("ChatViewModel", "CurrentTimeContext contains hour: true")

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
                val finalUserMessage = "$timeContext\n\nPERTANYAAN USER:\n$messageText"
                chatMessages.add(makeMessage("user", finalUserMessage, imageUri, true))

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
