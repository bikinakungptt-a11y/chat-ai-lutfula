package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CryptoPriceRepository(private val okHttpClient: OkHttpClient) {

    suspend fun getCryptoPrice(cryptoIds: List<String>): String = withContext(Dispatchers.IO) {
        val ids = cryptoIds.distinct().joinToString(",")
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=$ids&vs_currencies=usd,idr&include_market_cap=true&include_24hr_vol=true&include_24hr_change=true&include_last_updated_at=true"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AiChatMobile/1.0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Coingecko API failed with code: ${response.code}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response body")
            val json = JSONObject(body)

            val result = StringBuilder()
            cryptoIds.distinct().forEach { id ->
                appendCoinPrice(result, json, id, displayNameForId(id))
            }
            if (result.isEmpty()) {
                throw Exception("No data found for keywords")
            }
            result.toString().trim()
        }
    }

    suspend fun getCryptoPrice(coin: CryptoSearchResult): String = withContext(Dispatchers.IO) {
        val url = "https://api.coingecko.com/api/v3/simple/price?ids=${coin.id}&vs_currencies=usd,idr&include_market_cap=true&include_24hr_vol=true&include_24hr_change=true&include_last_updated_at=true"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AiChatMobile/1.0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Coingecko API failed with code: ${response.code}")
            }
            val body = response.body?.string() ?: throw Exception("Empty response body")
            val json = JSONObject(body)

            val result = StringBuilder()
            appendCoinPrice(result, json, coin.id, "${coin.symbol} (${coin.name})")
            if (result.isEmpty()) {
                throw Exception("No data found for ${coin.id}")
            }
            result.toString().trim()
        }
    }

    private fun appendCoinPrice(result: StringBuilder, json: JSONObject, id: String, name: String) {
        if (json.has(id)) {
            val data = json.getJSONObject(id)
            val usd = data.optDouble("usd", 0.0)
            val idr = data.optDouble("idr", 0.0)
            val change24h = data.optDouble("usd_24h_change", 0.0)
            val marketCap = data.optDouble("usd_market_cap", 0.0)
            val volume = data.optDouble("usd_24h_vol", 0.0)

            result.append("Harga $name realtime:\n\n")
            result.append("1 $name = $${usd}\n")
            result.append("1 $name = Rp${idr}\n")
            result.append("Perubahan 24 jam = $change24h%\n")
            result.append("Market cap = $${marketCap}\n")
            result.append("Volume 24 jam = $${volume}\n")
            result.append("CoinGecko id = $id\n")
            result.append("Sumber: CoinGecko realtime API\n\n")
        }
    }

    private fun displayNameForId(id: String): String {
        return when(id) {
            "bitcoin" -> "BTC"
            "ethereum" -> "ETH"
            "solana" -> "SOL"
            "binancecoin" -> "BNB"
            "ripple" -> "XRP"
            "dogecoin" -> "DOGE"
            "tether" -> "USDT"
            else -> id.replaceFirstChar { it.uppercase() }
        }
    }
}
