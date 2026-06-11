package com.example.data

import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

class AppwriteMemoryRepository(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient
) {
    private data class Config(
        val endpoint: String,
        val projectId: String,
        val databaseId: String,
        val collectionId: String
    ) {
        val documentsUrl: String
            get() = "$endpoint/databases/${encode(databaseId)}/collections/${encode(collectionId)}/documents"
    }

    private data class RemoteMemory(
        val documentId: String,
        val memory: MemoryEntity
    )

    suspend fun getAllMemories(): List<MemoryEntity> {
        return try {
            getRemoteMemories().map { it.memory }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveMemory(memory: MemoryEntity) {
        try {
            val config = getConfig() ?: return
            val data = JSONObject()
                .put("content", memory.content)
                .put("category", memory.category)
                .put("createdAt", memory.createdAt)
                .put("updatedAt", memory.updatedAt)
                .put("source", memory.source)
                .put("isPinned", memory.isPinned)

            val payload = JSONObject()
                .put("documentId", "unique()")
                .put("data", data)

            val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(config.documentsUrl)
                .addAppwriteHeaders(config)
                .post(body)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Keep local memory working even when Appwrite rejects the write.
                    return
                }
            }
        } catch (_: Exception) {
            // Cloud memory must never break local chat memory.
        }
    }

    suspend fun deleteAllMemories() {
        try {
            getRemoteMemories().forEach { remote ->
                deleteDocument(remote.documentId)
            }
        } catch (_: Exception) {
            // Ignore cloud delete failures so local delete can still complete.
        }
    }

    suspend fun deleteMemoriesByContent(query: String) {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return

        try {
            getRemoteMemories()
                .filter { it.memory.content.lowercase().contains(normalizedQuery) }
                .forEach { remote -> deleteDocument(remote.documentId) }
        } catch (_: Exception) {
            // Ignore cloud delete failures so local delete can still complete.
        }
    }

    private suspend fun getRemoteMemories(): List<RemoteMemory> {
        val config = getConfig() ?: return emptyList()
        val request = Request.Builder()
            .url("${config.documentsUrl}?limit=100")
            .addAppwriteHeaders(config)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val documents = JSONObject(body).optJSONArray("documents") ?: return emptyList()
            return buildList {
                for (index in 0 until documents.length()) {
                    val document = documents.optJSONObject(index) ?: continue
                    val content = document.optString("content", "").trim()
                    if (content.isBlank()) continue

                    add(
                        RemoteMemory(
                            documentId = document.optString("\$id", ""),
                            memory = MemoryEntity(
                                content = content,
                                category = document.optString("category", "manual"),
                                createdAt = document.optLong("createdAt", System.currentTimeMillis()),
                                updatedAt = document.optLong("updatedAt", System.currentTimeMillis()),
                                source = document.optString("source", "appwrite"),
                                isPinned = document.optBoolean("isPinned", false)
                            )
                        )
                    )
                }
            }
        }
    }

    private suspend fun deleteDocument(documentId: String) {
        if (documentId.isBlank()) return
        val config = getConfig() ?: return
        val request = Request.Builder()
            .url("${config.documentsUrl}/${encode(documentId)}")
            .addAppwriteHeaders(config)
            .delete()
            .build()

        okHttpClient.newCall(request).execute().use { /* close response */ }
    }

    private suspend fun getConfig(): Config? {
        if (!settingsRepository.appwriteMemoryEnabled.first()) return null

        val endpoint = settingsRepository.appwriteEndpoint.first().trim().trimEnd('/')
        val projectId = settingsRepository.appwriteProjectId.first().trim()
        val databaseId = settingsRepository.appwriteDatabaseId.first().trim()
        val collectionId = settingsRepository.appwriteMemoryCollectionId.first().trim()

        if (endpoint.isBlank() || projectId.isBlank() || databaseId.isBlank() || collectionId.isBlank()) {
            return null
        }

        return Config(
            endpoint = endpoint,
            projectId = projectId,
            databaseId = databaseId,
            collectionId = collectionId
        )
    }

    private fun Request.Builder.addAppwriteHeaders(config: Config): Request.Builder {
        return addHeader("X-Appwrite-Project", config.projectId)
            .addHeader("X-Appwrite-Response-Format", "1.6.0")
            .addHeader("Content-Type", "application/json")
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
