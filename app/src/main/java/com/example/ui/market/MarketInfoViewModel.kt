package com.example.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale

data class MarketInfoUiState(
    val priceData: String = "",
    val newsData: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

class MarketInfoViewModel(
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketInfoUiState())
    val uiState: StateFlow<MarketInfoUiState> = _uiState

    private val backendUrl = "https://chat-ai-lutfula.vercel.app"

    fun fetchMarketPrices() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,solana&vs_currencies=usd,idr&include_24hr_change=true")
                    .header("User-Agent", "AiChatMobile/1.0")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, error = "Gagal mengambil harga: HTTP ${response.code}") }
                    return@launch
                }

                val formatted = formatPrices(JSONObject(body))
                _uiState.update { it.copy(priceData = formatted, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = formatNetworkError(e)) }
            }
        }
    }

    fun fetchBtcNews() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$backendUrl/api/search?q=btc%20bitcoin%20news")
                    .header("User-Agent", "AiChatMobile/1.0")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    _uiState.update { it.copy(isLoading = false, error = "Gagal mengambil news BTC: HTTP ${response.code}") }
                    return@launch
                }

                val formatted = formatNews(body)
                _uiState.update { it.copy(newsData = formatted, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = formatNetworkError(e)) }
            }
        }
    }

    private fun formatPrices(json: JSONObject): String {
        val coins = listOf(
            Triple("bitcoin", "BTC", "Bitcoin"),
            Triple("ethereum", "ETH", "Ethereum"),
            Triple("solana", "SOL", "Solana")
        )
        return coins.joinToString("\n\n") { (id, symbol, name) ->
            val item = json.optJSONObject(id)
            if (item == null) {
                "$symbol ($name): data tidak tersedia"
            } else {
                val usd = item.optDouble("usd", Double.NaN)
                val idr = item.optDouble("idr", Double.NaN)
                val change = item.optDouble("usd_24h_change", Double.NaN)
                "$symbol ($name)\nUSD: ${formatUsd(usd)}\nIDR: ${formatIdr(idr)}\n24h: ${formatPercent(change)}"
            }
        } + "\n\nInfo harga hanya read-only."
    }

    private fun formatNews(body: String): String {
        return try {
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: json.optJSONArray("results")
            if (data == null || data.length() == 0) return "Tidak ada news BTC terbaru saat ini."
            val items = mutableListOf<String>()
            val maxItems = minOf(5, data.length())
            for (i in 0 until maxItems) {
                val item = data.optJSONObject(i) ?: continue
                val title = item.optString("title", "No title")
                val description = item.optString("description", "")
                val url = item.optString("url", "")
                items.add(buildString {
                    append("${i + 1}. $title")
                    if (description.isNotBlank()) append("\n$description")
                    if (url.isNotBlank()) append("\n$url")
                })
            }
            items.joinToString("\n\n") + "\n\nNews hanya informasi."
        } catch (e: Exception) {
            if (body.isBlank()) "News response kosong." else body.take(4000)
        }
    }

    private fun formatUsd(value: Double): String {
        if (value.isNaN()) return "N/A"
        return "$" + String.format(Locale.US, "%,.2f", value)
    }

    private fun formatIdr(value: Double): String {
        if (value.isNaN()) return "N/A"
        return "Rp " + String.format(Locale.US, "%,.0f", value)
    }

    private fun formatPercent(value: Double): String {
        if (value.isNaN()) return "N/A"
        val sign = if (value >= 0) "+" else ""
        return sign + String.format(Locale.US, "%.2f", value) + "%"
    }

    private fun formatNetworkError(e: Exception): String {
        val detail = e.message ?: "No detail"
        return "Network Error: ${e.javaClass.simpleName} - $detail"
    }

    class Factory(private val okHttpClient: OkHttpClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MarketInfoViewModel::class.java)) {
                return MarketInfoViewModel(okHttpClient) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
