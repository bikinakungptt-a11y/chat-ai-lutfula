package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/** Searches CoinGecko for a coin id by symbol/name query. */
data class CryptoSearchResult(
    val id: String,
    val symbol: String,
    val name: String,
    val marketCapRank: Int?
)

class CryptoSearchRepository(private val okHttpClient: OkHttpClient) {
    fun searchBestMatch(query: CryptoQuery): CryptoSearchResult? {
        val search = query.query.trim()
        if (search.isBlank()) return null

        val encodedQuery = URLEncoder.encode(search, "UTF-8")
        val request = Request.Builder()
            .url("https://api.coingecko.com/api/v3/search?query=$encodedQuery")
            .header("User-Agent", "AiChatMobile/1.0")
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful || body.isBlank()) {
            throw Exception("CoinGecko search failed with code: ${response.code}")
        }

        val coins = JSONObject(body).optJSONArray("coins") ?: return null
        if (coins.length() == 0) return null

        val normalized = search.lowercase()
        val candidates = mutableListOf<CryptoSearchResult>()
        for (i in 0 until minOf(coins.length(), 10)) {
            val item = coins.optJSONObject(i) ?: continue
            val rank = if (item.isNull("market_cap_rank")) null else item.optInt("market_cap_rank")
            candidates.add(
                CryptoSearchResult(
                    id = item.optString("id"),
                    symbol = item.optString("symbol").uppercase(),
                    name = item.optString("name"),
                    marketCapRank = rank
                )
            )
        }

        return candidates
            .filter { it.id.isNotBlank() }
            .sortedWith(
                compareByDescending<CryptoSearchResult> {
                    it.symbol.lowercase() == normalized || it.id.lowercase() == normalized || it.name.lowercase() == normalized
                }.thenBy { it.marketCapRank ?: Int.MAX_VALUE }
            )
            .firstOrNull()
    }
}
