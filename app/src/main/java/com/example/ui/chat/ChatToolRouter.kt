package com.example.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

private const val BACKEND_BASE_URL = "https://chat-ai-lutfula.vercel.app"

/**
 * Tool Router untuk ChatViewModel.
 *
 * Tugasnya memilih tool/backend yang tepat sebelum pesan dikirim ke model AI:
 * - URL/link -> backend /api/read-url
 * - Search/berita/web -> backend /api/search
 * - Tanggal merah/libur -> backend /api/holiday
 * - Crypto -> CoinGecko repository lokal
 *
 * API key Firecrawl dan API Ninjas tetap aman di backend, bukan di APK.
 */
class ChatToolRouter(
    private val okHttpClient: OkHttpClient,
    private val cryptoPriceRepository: com.example.data.CryptoPriceRepository,
    private val holidayRepository: com.example.data.HolidayRepository
) {
    suspend fun route(messageText: String): ChatToolResult = withContext(Dispatchers.IO) {
        val textLower = messageText.lowercase()
        val urls = extractUrls(messageText)

        when {
            urls.isNotEmpty() -> readUrl(urls.first())
            isHolidayQuery(textLower) -> checkHoliday(textLower)
            isCryptoQuery(textLower) -> checkCrypto(textLower)
            shouldUseRealtimeSearch(textLower) -> search(messageText)
            else -> ChatToolResult.none()
        }
    }

    private fun extractUrls(messageText: String): List<String> {
        return Regex("(https?://[\\w-]+(\\.[\\w-]+)+(/([\\w- ./?%&=]*)?)?)")
            .findAll(messageText)
            .map { it.value }
            .toList()
    }

    private fun shouldUseRealtimeSearch(textLower: String): Boolean {
        val keywords = listOf(
            "carikan website", "cari website", "carikan link", "cari link",
            "gunakan browser", "browsing", "cari di internet", "cari di web",
            "search web", "search internet", "cek website", "cek web", "sumber",
            "link resmi", "rekomendasi website", "website untuk", "dimana daftar",
            "cari api", "cari proxy", "cari model", "cari provider", "website",
            "web", "browser", "internet", "link", "berita terbaru", "news terbaru"
        )
        return keywords.any { keyword -> textLower.contains(keyword) }
    }

    private fun isHolidayQuery(textLower: String): Boolean {
        return Regex("\\b(tanggal merah|libur|working day|hari libur|suro|muharram|kalender)\\b")
            .containsMatchIn(textLower)
    }

    private fun isCryptoQuery(textLower: String): Boolean {
        return Regex("\\b(btc|bitcoin|eth|ethereum|sol|solana|bnb|xrp|ripple|doge|dogecoin|usdt|tether)\\b")
            .containsMatchIn(textLower)
    }

    private suspend fun readUrl(url: String): ChatToolResult {
        return try {
            val bodyJson = JSONObject().put("url", url).toString()
            val requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$BACKEND_BASE_URL/api/read-url")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseStr = response.body?.string().orEmpty()
                if (!response.isSuccessful || responseStr.isBlank()) {
                    return ChatToolResult.error("READ_URL", "Backend realtime belum tersedia atau gagal mengambil data.")
                }

                val json = JSONObject(responseStr)
                val markdown = json.optString("markdown", responseStr)
                val safeMarkdown = markdown.take(10000)
                ChatToolResult(
                    usedTool = true,
                    toolName = "READ_URL",
                    context = """
                        Backend Tool Result: READ_URL
                        Url: $url
                        Content:
                        $safeMarkdown

                        Instruction: Answer based on this website content. If the user only sent a link, summarize it.
                    """.trimIndent()
                )
            }
        } catch (e: Exception) {
            ChatToolResult.error("READ_URL", "Backend realtime belum tersedia atau gagal mengambil data.")
        }
    }

    private suspend fun search(query: String): ChatToolResult {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$BACKEND_BASE_URL/api/search?q=$encoded")
                .get()
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val responseStr = response.body?.string().orEmpty()
                if (!response.isSuccessful || responseStr.isBlank()) {
                    return ChatToolResult.error("SEARCH", "Backend realtime belum tersedia atau gagal mengambil data.")
                }

                val json = JSONObject(responseStr)
                val results = json.optJSONArray("results")
                if (results == null || results.length() == 0) {
                    return ChatToolResult(
                        usedTool = true,
                        toolName = "SEARCH",
                        context = "Search/browser berjalan tetapi tidak ada hasil relevan. Jangan mengarang data realtime."
                    )
                }

                val lines = mutableListOf<String>()
                val links = mutableListOf<String>()
                for (i in 0 until minOf(3, results.length())) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title", "No Title")
                    val description = item.optString("description", "")
                    val url = item.optString("url", "")
                    lines.add("- Title: $title\n  Description: $description\n  URL: $url")
                    links.add("• $title\n  $url")
                }

                ChatToolResult(
                    usedTool = true,
                    toolName = "SEARCH",
                    context = "Use the following real-time search results to answer the user's query:\n\n" + lines.joinToString("\n\n"),
                    sources = if (links.isNotEmpty()) "\n\nSources:\n" + links.joinToString("\n") else ""
                )
            }
        } catch (e: Exception) {
            ChatToolResult.error("SEARCH", "Backend realtime belum tersedia atau gagal mengambil data.")
        }
    }

    private fun checkHoliday(textLower: String): ChatToolResult {
        val targetDate = parseDateFromText(textLower)
        val holidayInfo = holidayRepository.isWorkingDay(targetDate)
        return ChatToolResult(
            usedTool = true,
            toolName = "HOLIDAY",
            context = """
                Backend Tool Result: HOLIDAY
                $holidayInfo

                Instruction: Use this backend holiday result to answer if the date is a holiday/tanggal merah/working day.
            """.trimIndent()
        )
    }

    private suspend fun checkCrypto(textLower: String): ChatToolResult {
        val cryptoIds = mutableListOf<String>()
        if (Regex("\\b(btc|bitcoin)\\b").containsMatchIn(textLower)) cryptoIds.add("bitcoin")
        if (Regex("\\b(eth|ethereum)\\b").containsMatchIn(textLower)) cryptoIds.add("ethereum")
        if (Regex("\\b(sol|solana)\\b").containsMatchIn(textLower)) cryptoIds.add("solana")
        if (Regex("\\b(bnb|binancecoin)\\b").containsMatchIn(textLower)) cryptoIds.add("binancecoin")
        if (Regex("\\b(xrp|ripple)\\b").containsMatchIn(textLower)) cryptoIds.add("ripple")
        if (Regex("\\b(doge|dogecoin)\\b").containsMatchIn(textLower)) cryptoIds.add("dogecoin")
        if (Regex("\\b(usdt|tether)\\b").containsMatchIn(textLower)) cryptoIds.add("tether")

        if (cryptoIds.isEmpty()) return ChatToolResult.none()

        return try {
            val cryptoData = cryptoPriceRepository.getCryptoPrice(cryptoIds)
            ChatToolResult(
                usedTool = true,
                toolName = "CRYPTO",
                context = """
                    Backend Tool Result: CRYPTO/PRICE
                    $cryptoData

                    Instruction: Use this realtime price data. Do not guess prices.
                """.trimIndent()
            )
        } catch (e: Exception) {
            ChatToolResult.error("CRYPTO", "Backend realtime belum tersedia atau gagal mengambil data.")
        }
    }

    private fun parseDateFromText(textLower: String): Date {
        val cal = java.util.Calendar.getInstance()
        cal.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        when {
            textLower.contains("besok") -> cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            textLower.contains("lusa") -> cal.add(java.util.Calendar.DAY_OF_YEAR, 2)
            textLower.contains("kemarin") -> cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }

        val match = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(textLower)
        return if (match != null) {
            val sdf = SimpleDateFormat("yyyy-MM-dd")
            sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
            sdf.parse(match.value) ?: cal.time
        } else {
            cal.time
        }
    }
}

data class ChatToolResult(
    val usedTool: Boolean,
    val toolName: String,
    val context: String,
    val sources: String = ""
) {
    companion object {
        fun none(): ChatToolResult = ChatToolResult(false, "NONE", "")
        fun error(toolName: String, message: String): ChatToolResult = ChatToolResult(
            usedTool = true,
            toolName = toolName,
            context = "Backend Tool Result: $toolName failed. $message"
        )
    }
}
