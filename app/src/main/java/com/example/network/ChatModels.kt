package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ReasoningConfig(
    val effort: String
)

@JsonClass(generateAdapter = true)
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val reasoning: ReasoningConfig? = null
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val role: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class ChatResponse(
    val id: String?,
    val choices: List<ChatChoice>?,
    val error: ChatError?
)

@JsonClass(generateAdapter = true)
data class ChatChoice(
    val index: Int?,
    val message: ChatMessage?,
    @param:Json(name = "finish_reason") val finishReason: String?
)

@JsonClass(generateAdapter = true)
data class ChatError(
    val message: String?
)

@JsonClass(generateAdapter = true)
data class FirecrawlSearchRequest(
    val query: String
)

@JsonClass(generateAdapter = true)
data class FirecrawlSearchResponse(
    val success: Boolean?,
    val data: List<FirecrawlSearchResult>?
)

@JsonClass(generateAdapter = true)
data class FirecrawlSearchResult(
    val url: String?,
    val title: String?,
    val description: String?
)
