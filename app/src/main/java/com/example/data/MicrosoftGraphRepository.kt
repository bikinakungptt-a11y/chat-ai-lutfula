package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query


data class GraphEmail(
    val id: String,
    val subject: String?,
    val bodyPreview: String?,
    val receivedDateTime: String?,
    val sender: GraphEmailSender?,
    val webLink: String?,
    val hasAttachments: Boolean?
)

data class GraphEmailSender(
    val emailAddress: GraphEmailAddress?
)

data class GraphEmailAddress(
    val address: String?,
    val name: String?
)

data class GraphEmailsResponse(
    val value: List<GraphEmail>
)

data class GraphAttachment(
    val id: String?,
    val name: String?,
    val contentType: String?,
    val size: Int?,
    val isInline: Boolean?
)

data class GraphAttachmentsResponse(
    val value: List<GraphAttachment>
)

data class GraphProfile(
    val id: String?,
    val displayName: String?,
    val mail: String?,
    val userPrincipalName: String?
)

interface MicrosoftGraphApi {
    @GET("v1.0/me")
    suspend fun getProfile(): GraphProfile

    @GET("v1.0/me/mailFolders/{folderId}/messages")
    suspend fun getMessages(
        @Path("folderId") folderId: String = "inbox",
        @Query("\$select") select: String = "subject,from,receivedDateTime,bodyPreview,webLink,hasAttachments,sender",
        @Query("\$top") top: Int = 25
    ): GraphEmailsResponse

    @GET("v1.0/me/messages")
    suspend fun searchMessages(
        @Query("\$search") search: String,
        @Query("\$select") select: String = "subject,from,receivedDateTime,bodyPreview,webLink,hasAttachments,sender",
        @Query("\$top") top: Int = 25
    ): GraphEmailsResponse

    @GET("v1.0/me/messages/{messageId}/attachments")
    suspend fun getAttachments(
        @Path("messageId") messageId: String,
        @Query("\$select") select: String = "id,name,contentType,size,isInline"
    ): GraphAttachmentsResponse
}

class MicrosoftGraphRepository(private val authService: MicrosoftAuthService) {
    private val _emails = MutableStateFlow<List<GraphEmail>>(emptyList())
    val emails: StateFlow<List<GraphEmail>> = _emails.asStateFlow()

    private val _attachmentsByMessageId = MutableStateFlow<Map<String, List<GraphAttachment>>>(emptyMap())
    val attachmentsByMessageId: StateFlow<Map<String, List<GraphAttachment>>> = _attachmentsByMessageId.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val graphApi: MicrosoftGraphApi by lazy {
        val authInterceptor = Interceptor { chain ->
            val accessToken = kotlinx.coroutines.runBlocking { authService.acquireTokenSilent() }
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
            if (accessToken != null) {
                newRequest.addHeader("Authorization", "Bearer $accessToken")
            }
            chain.proceed(newRequest.build())
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()

        Retrofit.Builder()
            .baseUrl("https://graph.microsoft.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MicrosoftGraphApi::class.java)
    }

    private fun graphErrorMessage(e: retrofit2.HttpException): String {
        return when (e.code()) {
            401 -> "Graph 401: token tidak valid / login ulang"
            403 -> "Graph 403: permission belum diberikan"
            404 -> "Graph 404: endpoint salah"
            else -> "Microsoft Graph error: ${e.code()}"
        }
    }

    suspend fun loadProfile(): String {
        _isLoading.value = true
        _error.value = null
        return try {
            val profile = graphApi.getProfile()
            val email = profile.mail ?: profile.userPrincipalName ?: "Unknown Email"
            val name = profile.displayName ?: "Unknown Name"
            android.util.Log.i("MSAL", "Graph profile success")
            "Profile Name: $name\nEmail: $email"
        } catch (e: retrofit2.HttpException) {
            val errMessage = graphErrorMessage(e)
            _error.value = errMessage
            errMessage
        } catch (e: Exception) {
            val errMessage = "Network error: ${e.message}"
            _error.value = errMessage
            errMessage
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun loadLatestEmails(folderId: String = "inbox") {
        _isLoading.value = true
        _error.value = null
        try {
            val response = graphApi.getMessages(folderId = folderId)
            _emails.value = response.value
            android.util.Log.i("MSAL", "Graph inbox success")
        } catch (e: retrofit2.HttpException) {
             _error.value = when (e.code()) {
                401 -> "Graph 401: token tidak valid / login ulang"
                403 -> "Graph 403: permission Mail.Read belum diberikan"
                404 -> "Graph 404: endpoint salah"
                else -> "Microsoft Graph error: ${e.code()}"
            }
        } catch (e: Exception) {
            _error.value = "Network error: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun searchEmails(query: String) {
        _isLoading.value = true
        _error.value = null
        try {
            val safeQuery = query.trim().ifBlank { "inbox" }
            val response = graphApi.searchMessages(search = "\"$safeQuery\"")
            _emails.value = response.value
        } catch (e: retrofit2.HttpException) {
             _error.value = when (e.code()) {
                401 -> "Graph 401: token tidak valid / login ulang"
                403 -> "Graph 403: permission Mail.Read belum diberikan"
                404 -> "Graph 404: endpoint salah"
                else -> "Microsoft Graph error: ${e.code()}"
            }
        } catch (e: Exception) {
            _error.value = "Network error: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun loadAttachmentsForEmail(messageId: String): List<GraphAttachment> {
        return try {
            val response = graphApi.getAttachments(messageId)
            val current = _attachmentsByMessageId.value.toMutableMap()
            current[messageId] = response.value
            _attachmentsByMessageId.value = current
            response.value
        } catch (e: retrofit2.HttpException) {
            _error.value = graphErrorMessage(e)
            emptyList()
        } catch (e: Exception) {
            _error.value = "Attachment error: ${e.message}"
            emptyList()
        }
    }

    suspend fun searchPdfEmails(folderId: String = "inbox") {
        _isLoading.value = true
        _error.value = null
        try {
            val response = graphApi.getMessages(folderId = folderId, top = 50)
            val attachmentEmails = response.value.filter { it.hasAttachments == true }
            val matchedEmails = attachmentEmails.filter { email ->
                val attachments = loadAttachmentsForEmail(email.id)
                attachments.any { attachment ->
                    val name = attachment.name.orEmpty().lowercase()
                    val type = attachment.contentType.orEmpty().lowercase()
                    name.endsWith(".pdf") || name.contains("pdf") || type.contains("pdf")
                } || email.subject.orEmpty().contains("pdf", ignoreCase = true) || email.bodyPreview.orEmpty().contains("pdf", ignoreCase = true)
            }
            _emails.value = matchedEmails.ifEmpty { attachmentEmails }
        } catch (e: retrofit2.HttpException) {
            _error.value = graphErrorMessage(e)
        } catch (e: Exception) {
            _error.value = "Network error: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }
}