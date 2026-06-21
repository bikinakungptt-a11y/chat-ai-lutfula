package com.example.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

class HolidayRepository(private val okHttpClient: OkHttpClient) {
    private val cache = mutableMapOf<String, String>()

    fun isWorkingDay(targetDate: Date): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        sdf.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        val dateStr = sdf.format(targetDate)

        if (cache.containsKey(dateStr)) {
            return cache[dateStr] ?: ""
        }

        val indonesia = fetchHoliday(dateStr, "ID", "Indonesia")
        val unitedStates = fetchHoliday(dateStr, "US", "United States")

        val result = "Holiday API Result for $dateStr:\n\n$indonesia\n\n$unitedStates\n\nInstruction: If the user asks about Indonesia, use the Indonesia result. If the user asks about US, USA, America, or United States, use the United States result. If the user does not specify a country, answer for Indonesia first and mention that US data is also available above."
        cache[dateStr] = result
        return result
    }

    private fun fetchHoliday(dateStr: String, country: String, label: String): String {
        return try {
            val request = Request.Builder()
                .url("https://chat-ai-lutfula.vercel.app/api/holiday?date=$dateStr&country=$country")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()

            if (response.isSuccessful && body != null) {
                val resultStr = try {
                    JSONObject(body).optString("result", body)
                } catch (e: Exception) {
                    body
                }
                "$label ($country): $resultStr"
            } else {
                "$label ($country): Backend realtime belum tersedia atau gagal mengambil data."
            }
        } catch (e: Exception) {
            "$label ($country): Backend realtime belum tersedia atau gagal mengambil data."
        }
    }
}
