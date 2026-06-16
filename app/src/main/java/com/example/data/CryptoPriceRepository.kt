package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class CryptoPriceRepository(private val okHttpClient: OkHttpClient) {
    private val serverBaseUrl = "https://chat-ai-lutfula.vercel.app"

    suspend fun getCryptoPrice(cryptoIds: List<String>): String = withContext(Dispatchers.IO) {
        val ids = cryptoIds.joinToString(",")
        val encodedIds = URLEncoder.encode(ids, "UTF-8")
        val request = Request.Builder()
            .url("$serverBaseUrl/api/btc?ids=$encodedIds&vs_currencies=usd,idr")
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response body")
            val root = JSONObject(body)
            if (!response.isSuccessful) {
                throw Exception(root.optString("error", "Backend failed with code: ${response.code}"))
            }

            val priceJson = root.optJSONObject("data") ?: root
            val result = StringBuilder()

            cryptoIds.forEach { id ->
                val data = priceJson.optJSONObject(id) ?: return@forEach
                val usd = data.optDouble("usd", 0.0)
                val idr = data.optDouble("idr", 0.0)
                val change24h = data.optDouble("usd_24h_change", 0.0)
                val marketCap = data.optDouble("usd_market_cap", 0.0)
                val volume = data.optDouble("usd_24h_vol", 0.0)

                val name = when (id) {
                    "bitcoin" -> "BTC"
                    "ethereum" -> "ETH"
                    "solana" -> "SOL"
                    "binancecoin" -> "BNB"
                    "ripple" -> "XRP"
                    "dogecoin" -> "DOGE"
                    "tether" -> "USDT"
                    else -> id.replaceFirstChar { it.uppercase() }
                }

                result.append("Harga $name realtime dari backend:\n\n")
                result.append("1 $name = $${usd}\n")
                result.append("1 $name = Rp${idr}\n")
                result.append("Perubahan 24 jam = $change24h%\n")
                result.append("Market cap = $${marketCap}\n")
                result.append("Volume 24 jam = $${volume}\n")
                result.append("Sumber: server Vercel\n\n")
            }

            if (result.isEmpty()) throw Exception("No data found for keywords")
            result.toString().trim()
        }
    }
}
