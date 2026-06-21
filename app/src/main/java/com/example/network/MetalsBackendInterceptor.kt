package com.example.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject

class MetalsBackendInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url

        val isMetalsLatestRequest = originalUrl.host == "api.metals.dev" &&
            originalUrl.encodedPath.contains("/v1/latest")

        if (!isMetalsLatestRequest) {
            return chain.proceed(originalRequest)
        }

        val apiKey = originalUrl.queryParameter("api_key").orEmpty().trim()
        val shouldUseBackend = apiKey.isBlank() ||
            apiKey == "YOUR_METALS_API_KEY" ||
            apiKey == "YOUR_METALS_DEV_API_KEY" ||
            apiKey == "VERCEL_BACKEND"

        if (!shouldUseBackend) {
            return chain.proceed(originalRequest)
        }

        val symbol = originalUrl.queryParameter("symbol") ?: "XAU"
        val currency = originalUrl.queryParameter("currency") ?: "USD"
        val unit = originalUrl.queryParameter("unit") ?: "toz"

        val backendUrl = originalUrl.newBuilder()
            .scheme("https")
            .host("chat-ai-lutfula.vercel.app")
            .encodedPath("/api/metals")
            .query(null)
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("currency", currency)
            .addQueryParameter("unit", unit)
            .build()

        val backendRequest = originalRequest.newBuilder()
            .url(backendUrl)
            .get()
            .build()

        val backendResponse = chain.proceed(backendRequest)
        val backendBody = backendResponse.body?.string().orEmpty()

        return try {
            val json = JSONObject(backendBody)
            val ok = json.optBoolean("success", json.optBoolean("ok", false))
            val price = json.optDouble("price", 0.0)
            if (backendResponse.isSuccessful && ok && price > 0.0) {
                val compatibleJson = JSONObject()
                    .put("metals", JSONObject().put("gold", price).put("XAU", price))
                    .put("rates", JSONObject().put("gold", price).put("XAU", price))
                    .put("source", json.optString("source", "metals.dev via Vercel backend"))
                    .toString()

                backendResponse.newBuilder()
                    .code(200)
                    .message("OK")
                    .body(compatibleJson.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            } else {
                backendResponse.newBuilder()
                    .body(backendBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            }
        } catch (e: Exception) {
            backendResponse.newBuilder()
                .body(backendBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }
    }
}
