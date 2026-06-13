package com.example.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiModelConfig(
    val modelName: String,
    val providerName: String = "",
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    val supportsImageGeneration: Boolean = false,
    val supportsVideoGeneration: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ReasoningConfig(
    val effort: String
)

@JsonClass(generateAdapter = true)
data class VisionContent(
    val type: String,
    val text: String? = null,
    @Json(name = "image_url") val imageUrl: VisionImageUrl? = null
)

@JsonClass(generateAdapter = true)
data class VisionImageUrl(
    val url: String
)

@JsonClass(generateAdapter = true)
data class ChatRequestMessage(
    val role: String,
    val content: List<VisionContent>
)

@JsonClass(generateAdapter = true)
data class ChatRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
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

@JsonClass(generateAdapter = true)
data class FirecrawlScrapeRequest(
    val url: String
)

@JsonClass(generateAdapter = true)
data class FirecrawlScrapeResponse(
    val success: Boolean?,
    val data: FirecrawlScrapeData?
)

@JsonClass(generateAdapter = true)
data class FirecrawlScrapeData(
    val markdown: String?,
    val metadata: FirecrawlScrapeMetadata?
)

@JsonClass(generateAdapter = true)
data class FirecrawlScrapeMetadata(
    val title: String?,
    val description: String?
)
