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

enum class ChatMode { NORMAL, THINK, THINK_DEEPLY }

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

private data class RealtimeCommand(
    val mode: String,
    val query: String
)

private data class MemoryCommand(
    val endpoint: String,
    val action: String,
    val content: String = "",
    val loadingText: String,
    val blockedMessage: String? = null
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
    private val _uiState = MutableStateFlow(ChatUiState(mode = try { ChatMode.valueOf(localStorage.getChatMode()) } catch (_: Exception) { ChatMode.NORMAL }))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    private var messageJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { chatRepository.allSessions.collect { sessions -> _uiState.update { it.copy(sessions = sessions) } } }
        viewModelScope.launch { settingsRepository.model.collect { model -> _uiState.update { it.copy(currentModel = model) } } }
        viewModelScope.launch { settingsRepository.savedModelsList.collect { models -> _uiState.update { it.copy(savedModelsList = models) } } }
    }

    fun updateSelectedModel(modelName: String) {
        viewModelScope.launch { settingsRepository.updateModel(modelName) }
    }

    fun selectSession(sessionId: Long) {
        _uiState.update { it.copy(currentSessionId = sessionId, messages = emptyList()) }
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            chatRepository.getMessagesForSession(sessionId).collect { messages ->
                _uiState.update { state ->
                    if (state.currentSessionId == sessionId) state.copy(messages = messages.map { UiMessage(it.id.toString(), it.role, it.content, it.imageUri) }) else state
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
            if (_uiState.value.currentSessionId == sessionId) createNewSession()
        }
    }

    fun setMode(mode: ChatMode) {
        _uiState.update { it.copy(mode = mode) }
        localStorage.saveChatMode(mode.name)
    }

    private fun realtimeQuery(textInput: String): RealtimeCommand? {
        val text = textInput.trim()
        if (text.isEmpty()) return null
        val parts = text.split(Regex("\\s+"), limit = 2)
        val first = parts.firstOrNull()?.lowercase() ?: return null
        val mode = when (first) {
            "#berita" -> "berita"
            "#browser" -> "browser"
            "#cari" -> "cari"
            else -> return null
        }
        return RealtimeCommand(mode = mode, query = parts.getOrNull(1)?.trim() ?: "")
    }

    private fun cryptoQuery(textInput: String): String? {
        val text = textInput.trim()
        if (text.isEmpty() || text.startsWith("#")) return null

        val lower = text.lowercase()
        val coinKeywords = listOf(
            "btc", "bitcoin",
            "eth", "ethereum",
            "sol", "solana",
            "bnb", "binancecoin",
            "xrp", "ripple",
            "doge", "dogecoin",
            "usdt", "tether",
            "ada", "cardano",
            "trx", "tron",
            "ton", "toncoin",
            "matic", "polygon",
            "shib", "shiba",
            "pepe"
        )
        val hasCoin = coinKeywords.any { keyword ->
            Regex("\\b${Regex.escape(keyword)}\\b").containsMatchIn(lower)
        }
        val hasCryptoIntent = listOf(
            "crypto",
            "kripto",
            "cryptocurrency",
            "harga crypto",
            "harga kripto",
            "price crypto"
        ).any { lower.contains(it) }

        return if (hasCoin || hasCryptoIntent) text else null
    }

    private fun sensitiveMemoryWarning(content: String): String? {
        val lower = content.lowercase()
        val sensitive = listOf(
            "password", "passwd", "kata sandi", "sandi",
            "api key", "apikey", "token", "secret", "private key",
            "alamat", "address", "telepon", "phone", "nomor hp",
            "bank", "rekening", "credit card", "kartu kredit", "payment"
        )
        return if (sensitive.any { lower.contains(it) }) {
            "⚠️ Saya tidak menyimpan data sensitif seperti API key, password, token, alamat, nomor telepon, atau data bank."
        } else null
    }

    private fun memoryCommand(textInput: String): MemoryCommand? {
        val text = textInput.trim()
        if (text.isEmpty() || text.startsWith("#")) return null
        val lower = text.lowercase()

        if (lower == "cek memory cloud" || lower == "test memory cloud" || lower == "cek appwrite" || lower == "appwrite test") {
            return MemoryCommand(
                endpoint = "memory-cloud",
                action = "test",
                content = text,
                loadingText = "Mengecek memory cloud..."
            )
        }

        if (lower == "memory off") {
            return MemoryCommand("memory", "disable", text, "Mematikan memory...")
        }
        if (lower == "memory on") {
            return MemoryCommand("memory", "enable", text, "Mengaktifkan memory...")
        }
        if (lower == "hapus memory") {
            return MemoryCommand("memory", "clear", text, "Menghapus memory...")
        }
        if (lower == "lihat memory" || lower == "debug lokal") {
            return MemoryCommand("memory", "list", text, "Membaca memory...")
        }

        val savePrefixes = listOf("ingat:", "ingat ini:", "simpan dimemory anda:", "remember:")
        for (prefix in savePrefixes) {
            if (lower.startsWith(prefix)) {
                val content = text.substring(prefix.length).trim()
                if (content.isBlank()) {
                    return MemoryCommand("memory", "empty", text, "Menyimpan memory...", "Isi memory kosong. Tulis setelah $prefix")
                }
                val warning = sensitiveMemoryWarning(content)
                if (warning != null) {
                    return MemoryCommand("memory", "blocked", content, "Menyimpan memory...", warning)
                }
                return MemoryCommand("memory", "save", content, "Menyimpan memory...")
            }
        }

        val deletePrefixes = listOf("lupakan:")
        for (prefix in deletePrefixes) {
            if (lower.startsWith(prefix)) {
                val content = text.substring(prefix.length).trim()
                if (content.isBlank()) {
                    return MemoryCommand("memory", "empty", text, "Menghapus memory...", "Isi yang ingin dilupakan kosong. Tulis setelah $prefix")
                }
                return MemoryCommand("memory", "delete", content, "Menghapus memory...")
            }
        }

        return null
    }

    private fun firstWebsiteRoot(textInput: String): String? {
        val raw = Regex("https?://[^\\s]+").find(textInput)?.value ?: return null
        val cleaned = raw.trimEnd('.', ',', ')', ']', '}', '"')
        return try {
            val uri = java.net.URI(cleaned)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            if (scheme != "http" && scheme != "https") return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (_: Exception) {
            null
        }
    }

    private fun formatUsd(value: Double): String {
        return if (value.isNaN() || value <= 0.0) "-" else "${'$'}" + java.lang.String.format(java.util.Locale.US, "%,.2f", value)
    }

    private fun formatIdr(value: Double): String {
        return if (value.isNaN() || value <= 0.0) "-" else "Rp" + java.lang.String.format(java.util.Locale("id", "ID"), "%,.0f", value)
    }

    private fun formatPercent(value: Double): String {
        if (value.isNaN()) return "-"
        val prefix = if (value > 0.0) "+" else ""
        return prefix + java.lang.String.format(java.util.Locale.US, "%.2f", value) + "%"
    }

    private fun formatRealtime(raw: String, query: String): String {
        return try {
            val json = org.json.JSONObject(raw)
            val arr = json.optJSONArray("data") ?: json.optJSONArray("results")
                ?: return "Search realtime berjalan, tetapi hasil belum bisa dibaca."
            if (arr.length() == 0) return "Belum ada hasil realtime yang relevan untuk: $query"
            val out = StringBuilder("Hasil pencarian realtime untuk: $query\n\n")
            for (i in 0 until minOf(20, arr.length())) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title", "Tanpa judul")
                val desc = item.optString("description", item.optString("snippet", ""))
                val link = item.optString("url", item.optString("sourceURL", ""))
                out.append("${i + 1}. $title\n")
                if (desc.isNotBlank()) out.append("   ${desc.take(240)}\n")
                if (link.isNotBlank()) out.append("   Sumber: $link\n")
                out.append("\n")
            }
            out.toString().trim()
        } catch (e: Exception) {
            "Realtime search gagal membaca hasil: ${e.message}"
        }
    }

    private fun formatCrypto(raw: String, query: String): String {
        return try {
            val json = org.json.JSONObject(raw)
            val error = json.optString("error", "")
            if (error.isNotBlank()) {
                val message = json.optString("message", "")
                return if (message.isBlank()) "Crypto API gagal: $error" else "Crypto API gagal: $error\n$message"
            }

            val arr = json.optJSONArray("data") ?: return "Crypto API berjalan, tetapi hasil belum bisa dibaca."
            if (arr.length() == 0) return "Belum ada data crypto yang cocok untuk: $query"

            val source = json.optString("source", "CoinGecko")
            val out = StringBuilder("Harga crypto realtime untuk: $query\n\n")
            for (i in 0 until minOf(5, arr.length())) {
                val item = arr.optJSONObject(i) ?: continue
                val symbol = item.optString("symbol", item.optString("id", "crypto")).uppercase()
                val name = item.optString("name", symbol)
                val usd = item.optDouble("usd", Double.NaN)
                val idr = item.optDouble("idr", Double.NaN)
                val change24h = item.optDouble("usd_24h_change", Double.NaN)
                val marketCap = item.optDouble("usd_market_cap", Double.NaN)
                val volume = item.optDouble("usd_24h_vol", Double.NaN)

                out.append("${i + 1}. $name ($symbol)\n")
                out.append("   1 $symbol = ${formatUsd(usd)}\n")
                out.append("   1 $symbol = ${formatIdr(idr)}\n")
                out.append("   Perubahan 24 jam = ${formatPercent(change24h)}\n")
                out.append("   Market cap = ${formatUsd(marketCap)}\n")
                out.append("   Volume 24 jam = ${formatUsd(volume)}\n\n")
            }
            out.append("Sumber: $source realtime API")
            out.toString().trim()
        } catch (e: Exception) {
            "Crypto API gagal membaca hasil: ${e.message}"
        }
    }

    private fun formatMemory(raw: String, cloud: Boolean): String {
        return try {
            val json = org.json.JSONObject(raw)
            val error = json.optString("error", "")
            if (error.isNotBlank()) {
                val message = json.optString("message", "")
                return if (message.isBlank()) "Memory API gagal: $error" else "Memory API gagal: $error\n$message"
            }

            val message = json.optString("message", "")
            val status = json.optString("status", "")
            val title = if (cloud) "Memory cloud" else "Memory"
            val out = StringBuilder()
            if (message.isNotBlank()) out.append(message) else out.append("$title selesai diproses.")
            if (status.isNotBlank()) out.append("\nStatus: $status")

            val arr = json.optJSONArray("memories") ?: json.optJSONArray("data") ?: json.optJSONArray("documents")
            if (arr != null && arr.length() > 0) {
                out.append("\n\nDaftar memory:\n")
                for (i in 0 until minOf(20, arr.length())) {
                    val item = arr.optJSONObject(i)
                    val content = item?.optString("content")
                        ?: item?.optString("text")
                        ?: item?.optString("memory")
                        ?: item?.optString("summary")
                        ?: item?.toString()
                        ?: ""
                    if (content.isNotBlank()) out.append("- ${content.take(300)}\n")
                }
            }

            out.toString().trim()
        } catch (e: Exception) {
            "Memory API gagal membaca hasil: ${e.message}"
        }
    }

    private fun formatWebsiteContext(raw: String, rootUrl: String): String {
        return try {
            val json = org.json.JSONObject(raw)
            val arr = json.optJSONArray("data") ?: json.optJSONArray("results")
                ?: return "WEBSITE_CONTEXT_FROM_BACKEND:\nRoot website: $rootUrl\nData website tidak tersedia."
            val out = StringBuilder()
            out.append("WEBSITE_CONTEXT_FROM_BACKEND:\n")
            out.append("Root website: $rootUrl\n")
            for (i in 0 until minOf(3, arr.length())) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title", "Tanpa judul")
                val desc = item.optString("description", item.optString("snippet", ""))
                val link = item.optString("url", item.optString("sourceURL", ""))
                val reader = item.optString("reader", "backend")
                out.append("\nSumber ${i + 1}: $title\n")
                out.append("URL: ${if (link.isBlank()) rootUrl else link}\n")
                out.append("Reader: $reader\n")
                if (desc.isNotBlank()) out.append("Isi ringkas halaman: ${desc.take(900)}\n")
            }
            out.toString().trim()
        } catch (e: Exception) {
            "WEBSITE_CONTEXT_FROM_BACKEND:\nRoot website: $rootUrl\nGagal membaca data website: ${e.message}"
        }
    }

    private fun searchViaVercel(command: RealtimeCommand): Result<String> {
        return try {
            val encoded = java.net.URLEncoder.encode(command.query, "UTF-8")
            val mode = java.net.URLEncoder.encode(command.mode, "UTF-8")
            val request = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/search?q=$encoded&mode=$mode")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            val bodyText = response.body?.string().orEmpty()
            if (response.isSuccessful && bodyText.isNotBlank()) {
                Result.success(formatRealtime(bodyText, command.query))
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${bodyText.take(250)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cryptoViaVercel(query: String): Result<String> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/crypto?q=$encoded")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            val bodyText = response.body?.string().orEmpty()
            if (response.isSuccessful && bodyText.isNotBlank()) {
                Result.success(formatCrypto(bodyText, query))
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${bodyText.take(250)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun memoryViaVercel(command: MemoryCommand): Result<String> {
        return try {
            val json = org.json.JSONObject()
                .put("action", command.action)
                .put("content", command.content)
                .put("command", command.content)
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/${command.endpoint}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()
            val response = okHttpClient.newCall(request).execute()
            val bodyText = response.body?.string().orEmpty()
            if (response.isSuccessful && bodyText.isNotBlank()) {
                Result.success(formatMemory(bodyText, command.endpoint == "memory-cloud"))
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${bodyText.take(250)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun readWebsiteViaVercel(rootUrl: String): Result<String> {
        return try {
            val encoded = java.net.URLEncoder.encode(rootUrl, "UTF-8")
            val request = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/search?q=$encoded&url=$encoded")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            val bodyText = response.body?.string().orEmpty()
            if (response.isSuccessful && bodyText.isNotBlank()) {
                Result.success(formatWebsiteContext(bodyText, rootUrl))
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${bodyText.take(250)}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun sendMessage(userText: String, imageUri: String? = null) {
        val text = userText.trim()
        if (text.isEmpty() && imageUri == null) return
        val oldMessages = _uiState.value.messages.toList()
        _uiState.update { it.copy(isLoading = true, loadingText = null, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                var sessionId = _uiState.value.currentSessionId
                if (sessionId == null) {
                    val title = if (text.length > 20) text.substring(0, 20) + "..." else text.ifBlank { "New Chat" }
                    sessionId = chatRepository.createNewSession(title)
                    _uiState.update { it.copy(currentSessionId = sessionId) }
                    selectSession(sessionId)
                }

                chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "user", content = text, imageUri = imageUri))

                val realtime = realtimeQuery(text)
                if (realtime != null) {
                    if (realtime.query.isBlank()) {
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = "Masukkan kata kunci setelah #berita, #browser, atau #cari."))
                        _uiState.update { it.copy(isLoading = false, loadingText = null) }
                        return@launch
                    }
                    _uiState.update { it.copy(loadingText = if (realtime.mode == "berita") "Mencari berita hari ini..." else "Mencari data realtime...") }
                    val answer = searchViaVercel(realtime).getOrElse { e -> "Search realtime gagal dari backend Vercel.\n\n${e.message}" }
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = answer))
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                    return@launch
                }

                val memory = if (imageUri == null) memoryCommand(text) else null
                if (memory != null) {
                    if (memory.blockedMessage != null) {
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = memory.blockedMessage))
                        _uiState.update { it.copy(isLoading = false, loadingText = null) }
                        return@launch
                    }
                    _uiState.update { it.copy(loadingText = memory.loadingText) }
                    val answer = memoryViaVercel(memory).getOrElse { e -> "Memory gagal dari backend Vercel.\n\n${e.message}" }
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = answer))
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                    return@launch
                }

                val crypto = if (imageUri == null) cryptoQuery(text) else null
                if (crypto != null) {
                    _uiState.update { it.copy(loadingText = "Mencari harga crypto...") }
                    val answer = cryptoViaVercel(crypto).getOrElse { e -> "Crypto realtime gagal dari backend Vercel.\n\n${e.message}" }
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = answer))
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                    return@launch
                }

                val providerCredential = settingsRepository.apiKey.first()
                val baseUrl = settingsRepository.baseUrl.first()
                val path = settingsRepository.textPath.first()
                val modelName = settingsRepository.model.first()
                val models = settingsRepository.savedModelsList.first()
                val selected = models.find { it.modelName == modelName }
                val supportsVision = selected?.supportsVision ?: false
                val supportsReasoning = selected?.supportsReasoning ?: false
                val langPref = settingsRepository.assistantLanguagePreference.first()

                if (providerCredential.isBlank() || baseUrl.isBlank() || modelName.isBlank()) {
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = "Konfigurasi provider chat belum lengkap.") }
                    return@launch
                }

                val websiteRoot = firstWebsiteRoot(text)
                val websiteContext = if (websiteRoot != null) {
                    _uiState.update { it.copy(loadingText = "Membaca website...") }
                    readWebsiteViaVercel(websiteRoot).getOrElse { e ->
                        "WEBSITE_CONTEXT_FROM_BACKEND:\nRoot website: $websiteRoot\nGagal membaca website dari backend: ${e.message}"
                    }
                } else null

                var systemPrompt = when (_uiState.value.mode) {
                    ChatMode.NORMAL -> "You are a helpful AI assistant. Provide fast, simple, and direct answers."
                    ChatMode.THINK -> "You are a helpful AI assistant. Reason carefully but keep the final answer clear."
                    ChatMode.THINK_DEEPLY -> "You are a helpful AI assistant. Provide deeper analysis for complex tasks."
                }
                if (langPref == "id") systemPrompt += "\n\nAlways respond in Bahasa Indonesia."
                systemPrompt += "\n\n" + AppGuide.TEXT
                systemPrompt += "\n\n" + getCurrentTimeContext()
                if (websiteRoot != null) {
                    systemPrompt += "\n\nJika user mengirim link website, jelaskan fungsi website berdasarkan WEBSITE_CONTEXT_FROM_BACKEND. Fokus pada root domain, bukan hanya path link. Jawab dengan jelas: fungsi website, untuk apa website itu dipakai, fitur/isi penting yang terlihat, lalu akhiri dengan kalimat 'Kesimpulan: website ini fungsinya ...'. Jangan mengarang jika data tidak cukup."
                }

                val chatMessages = mutableListOf<com.example.network.ChatRequestMessage>()
                chatMessages.add(com.example.network.ChatRequestMessage(role = "system", content = listOf(com.example.network.VisionContent(type = "text", text = systemPrompt))))
                oldMessages.filter { !it.content.startsWith("⚠️") }.forEach {
                    chatMessages.add(com.example.network.ChatRequestMessage(role = it.role, content = listOf(com.example.network.VisionContent(type = "text", text = it.content))))
                }

                if (imageUri != null && !supportsVision) {
                    val msg = "⚠️ Model ini tidak mendukung membaca gambar. Pilih model vision."
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = msg))
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = msg) }
                    return@launch
                }

                val finalUserText = if (websiteRoot != null && websiteContext != null) {
                    """
User mengirim link:
$text

Root website yang harus dijelaskan:
$websiteRoot

$websiteContext

Tugas:
Jelaskan fungsi website tersebut dengan jelas. Jangan hanya merangkum pendek. Terangkan untuk apa website itu, apa fungsi utamanya, dan jika data cukup jelaskan fitur/layanan yang terlihat. Di bagian akhir wajib beri keterangan:
Kesimpulan: website ini fungsinya ...
""".trimIndent()
                } else text

                val userParts = mutableListOf<com.example.network.VisionContent>()
                userParts.add(com.example.network.VisionContent(type = "text", text = finalUserText))
                if (imageUri != null) {
                    val dataUrl = uriToBase64(imageUri)
                    if (dataUrl != null) userParts.add(com.example.network.VisionContent(type = "image_url", imageUrl = com.example.network.VisionImageUrl(url = dataUrl)))
                }
                chatMessages.add(com.example.network.ChatRequestMessage(role = "user", content = userParts))

                val reasoning = if (supportsReasoning) {
                    when (_uiState.value.mode) {
                        ChatMode.THINK -> ReasoningConfig("medium")
                        ChatMode.THINK_DEEPLY -> ReasoningConfig("high")
                        ChatMode.NORMAL -> null
                    }
                } else null

                val endpoint = baseUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
                val requestBody = ChatRequest(model = modelName, messages = chatMessages, reasoning = reasoning)
                val json = moshi.adapter(ChatRequest::class.java).toJson(requestBody)
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authori" + "zation", "Bear" + "er " + providerCredential)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    val chatResponse = moshi.adapter(ChatResponse::class.java).fromJson(responseBody)
                    val reply = chatResponse?.choices?.firstOrNull()?.message?.content ?: chatResponse?.error?.message ?: "No content received."
                    chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = reply))
                    _uiState.update { it.copy(isLoading = false, loadingText = null) }
                } else {
                    val errorMsg = if (response.code == 503) "HTTP 503: Provider/model sedang unavailable. Coba mode Normal atau model lain." else "HTTP ${response.code}: $responseBody"
                    _uiState.update { it.copy(isLoading = false, loadingText = null, error = errorMsg) }
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoading = false, loadingText = null, error = "Network Error or Timeout: ${e.message}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, loadingText = null, error = "An unexpected error occurred: ${e.message}") }
            }
        }
    }

    private fun uriToBase64(uriStr: String?): String? {
        return try {
            val uri = android.net.Uri.parse(uriStr)
            val resolver = applicationContext.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT or android.util.Base64.NO_WRAP)
                val mimeType = resolver.getType(uri) ?: "image/jpeg"
                "data:$mimeType;base64,$base64"
            } else null
        } catch (_: Exception) {
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
    val zoneId = try { java.time.ZoneId.systemDefault() } catch (_: Exception) { java.time.ZoneId.of("Asia/Jakarta") }
    val now = java.time.ZonedDateTime.now(zoneId)
    val dateStr = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", java.util.Locale.forLanguageTag("id-ID")))
    val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    return """
        CURRENT_REAL_TIME_CONTEXT:
        Tanggal sekarang: $dateStr
        Jam sekarang: $timeStr
        Timezone: ${zoneId.id}
        Country code: ID
    """.trimIndent()
}
