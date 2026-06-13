package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

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
        @retrofit2.http.Path("folderId") folderId: String = "inbox",
        @Query("\$select") select: String = "subject,from,receivedDateTime,bodyPreview,webLink,hasAttachments,sender",
        @Query("\$top") top: Int = 25
    ): GraphEmailsResponse

    @GET("v1.0/me/messages")
    suspend fun searchMessages(
        @Query("\$search") search: String,
        @Query("\$select") select: String = "subject,from,receivedDateTime,bodyPreview,webLink,hasAttachments,sender",
        @Query("\$top") top: Int = 25
    ): GraphEmailsResponse
}

class MicrosoftGraphRepository(private val authService: MicrosoftAuthService) {
    private val _emails = MutableStateFlow<List<GraphEmail>>(emptyList())
    val emails: StateFlow<List<GraphEmail>> = _emails.asStateFlow()

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

    suspend fun loadProfile(): String {
        _isLoading.value = true
        _error.value = null
        return try {
            val profile = graphApi.getProfile()
            val email = profile.mail ?: profile.userPrincipalName ?: "Unknown Email"
            val name = profile.displayName ?: "Unknown Name"
            "Profile Name: $name\nEmail: $email"
        } catch (e: retrofit2.HttpException) {
            val errMessage = when (e.code()) {
                401 -> "Unauthorized. Please login again."
                403 -> "Forbidden. Need permission to access this resource."
                404 -> "Resource not found on Microsoft Graph."
                else -> "Microsoft Graph error: ${e.code()}"
            }
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
        } catch (e: retrofit2.HttpException) {
             _error.value = when (e.code()) {
                401 -> "Unauthorized. Please login again."
                403 -> "Forbidden. Need permission to access inbox."
                404 -> "Inbox not found."
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
            val response = graphApi.searchMessages(search = "\"$query\"")
            _emails.value = response.value
        } catch (e: retrofit2.HttpException) {
             _error.value = when (e.code()) {
                401 -> "Unauthorized. Please login again."
                403 -> "Forbidden. Need permission to search."
                404 -> "Endpoint not found."
                else -> "Microsoft Graph error: ${e.code()}"
            }
        } catch (e: Exception) {
            _error.value = "Network error: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }
}
