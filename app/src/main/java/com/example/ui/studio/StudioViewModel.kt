package com.example.ui.studio

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlinx.coroutines.delay

data class StudioUiState(
    val selectedTab: Int = 0, // 0: Photo, 1: Edit, 2: Video
    val prompt: String = "",
    val generatedMediaUrl: String? = null, // Temporary store for generated image
    val generatedVideoUrl: String? = null,
    val isGenerating: Boolean = false,
    val error: String? = null,
    val selectedImageUri: Uri? = null, // Selected from user's device
    val videoStatus: String? = null
)

class StudioViewModel(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val applicationContext: android.content.Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    fun selectTab(index: Int) {
        _uiState.update { it.copy(selectedTab = index, error = null, videoStatus = null) }
    }

    fun updatePrompt(text: String) {
        _uiState.update { it.copy(prompt = text) }
    }

    fun selectImage(uri: Uri?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun useGeneratedPhoto() {
        // Keeps generatedMediaUrl, can be used by video
    }

    private fun getFileFromUri(uri: Uri, economyMode: Boolean = false): File? {
        try {
            val tempFile = File.createTempFile("upload", ".png", applicationContext.cacheDir)
            if (economyMode) {
                val bitmap = android.graphics.BitmapFactory.decodeStream(applicationContext.contentResolver.openInputStream(uri))
                if (bitmap != null) {
                    var scaledBitmap = bitmap
                    if (bitmap.width > 1024 || bitmap.height > 1024) {
                        val ratio = minOf(1024f / bitmap.width, 1024f / bitmap.height)
                        scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    }
                    FileOutputStream(tempFile).use { out ->
                        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                    }
                    return tempFile
                }
            }
            
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            return tempFile
        } catch (e: Exception) {
            return null
        }
    }

    private fun getBase64FromUri(uri: Uri, economyMode: Boolean = false): String? {
        return try {
            val mimeType = applicationContext.contentResolver.getType(uri) ?: "image/jpeg"
            val base64Str = if (economyMode) {
                val bitmap = android.graphics.BitmapFactory.decodeStream(applicationContext.contentResolver.openInputStream(uri))
                if (bitmap != null) {
                    var scaledBitmap = bitmap
                    if (bitmap.width > 1024 || bitmap.height > 1024) {
                        val ratio = minOf(1024f / bitmap.width, 1024f / bitmap.height)
                        scaledBitmap = android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                    }
                    val outputStream = java.io.ByteArrayOutputStream()
                    scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
                    Base64.getEncoder().encodeToString(outputStream.toByteArray())
                } else null
            } else {
                val bytes = applicationContext.contentResolver.openInputStream(uri)?.readBytes()
                if (bytes != null) {
                    Base64.getEncoder().encodeToString(bytes)
                } else null
            }
            if (base64Str != null) {
                val finalMime = if (economyMode) "image/jpeg" else mimeType
                "data:$finalMime;base64,$base64Str"
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getBase64FromUrl(urlString: String): String? {
        return try {
            val request = Request.Builder().url(urlString).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val mimeType = response.header("Content-Type") ?: "image/jpeg"
                val bytes = response.body?.bytes()
                if (bytes != null) {
                    val base64Str = Base64.getEncoder().encodeToString(bytes)
                    "data:$mimeType;base64,$base64Str"
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun generate() {
        val state = _uiState.value
        if (state.isGenerating) return
        
        _uiState.update { it.copy(isGenerating = true, error = null, videoStatus = null) }
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (state.selectedTab) {
                    0 -> generateImage(state.prompt)
                    1 -> editImage(state.prompt, state.selectedImageUri)
                    2 -> generateVideo(state.prompt, state.selectedImageUri ?: state.generatedMediaUrl?.let { Uri.parse(it) })
                }
            } catch (e: Exception) {
                val safeMsg = when {
                    e.message?.contains("timeout", ignoreCase = true) == true -> "Network timeout. Please try again."
                    else -> "Generation failed: ${e.localizedMessage}"
                }
                _uiState.update { it.copy(isGenerating = false, error = safeMsg) }
            }
        }
    }

    private suspend fun generateImage(prompt: String) {
        if (prompt.isBlank()) {
            _uiState.update { it.copy(isGenerating = false, error = "Please enter a prompt first.") }
            return
        }
        val baseUrl = settingsRepository.createPhotoBaseUrl.first().trimEnd('/')
        val endpoint = settingsRepository.createPhotoEndpoint.first().removePrefix("/")
        val apiKey = settingsRepository.createPhotoApiKey.first()
        val model = settingsRepository.createPhotoModel.first()
        val format = settingsRepository.createPhotoFormat.first()
        val economyMode = settingsRepository.economyMode.first()

        if (baseUrl.isBlank() || endpoint.isBlank() || apiKey.isBlank()) {
            _uiState.update { it.copy(isGenerating = false, error = "Please configure Create Photo Settings first.") }
            return
        }

        val url = "$baseUrl/$endpoint"
         val requestBody = if (format == "multipart") {
             val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("model", model)
             if (economyMode) builder.addFormDataPart("n", "1")
             builder.build()
        } else {
            JSONObject().apply {
                put("prompt", prompt)
                if (model.isNotBlank()) put("model", model)
                if (economyMode) put("n", 1)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
            
        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        
        android.util.Log.d("StudioViewModel", "Create Photo API response: ${response.code} $responseBody")
        
        handleImageResponse(response.code, responseBody)
    }
    
    private suspend fun editImage(prompt: String, imageUri: Uri?) {
        if (prompt.isBlank()) {
            _uiState.update { it.copy(isGenerating = false, error = "Please enter a prompt first.") }
            return
        }
        if (imageUri == null) {
            _uiState.update { it.copy(isGenerating = false, error = "Please select a photo first.") }
            return
        }
        
        val baseUrl = settingsRepository.editPhotoBaseUrl.first().trimEnd('/')
        val endpoint = settingsRepository.editPhotoEndpoint.first().removePrefix("/")
        val apiKey = settingsRepository.editPhotoApiKey.first()
        val model = settingsRepository.editPhotoModel.first()
        val format = settingsRepository.editPhotoFormat.first()
        val imageFormatSetting = settingsRepository.editPhotoImageFormat.first()
        val economyMode = settingsRepository.economyMode.first()

        if (baseUrl.isBlank() || endpoint.isBlank() || apiKey.isBlank()) {
            _uiState.update { it.copy(isGenerating = false, error = "Please configure Edit Photo Settings first.") }
            return
        }

        val url = "$baseUrl/$endpoint"
        android.util.Log.d("StudioViewModel", "Edit Photo URL: $url | Format: $format | ImageFormat: $imageFormatSetting")
        android.util.Log.d("StudioViewModel", "Edit Photo URI type: ${if (imageUri.toString().startsWith("http")) "http/https" else if (imageUri.toString().startsWith("content://")) "content://" else "file://"}")
        
        val requestBody = if (format == "multipart") {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            if (prompt.isNotBlank()) builder.addFormDataPart("prompt", prompt)
            if (model.isNotBlank()) builder.addFormDataPart("model", model)
            
            val tempFile = getFileFromUri(imageUri, economyMode)
            if (tempFile != null) {
                builder.addFormDataPart("image", "image.png", tempFile.asRequestBody("image/png".toMediaType()))
            }
            builder.build()
        } else {
            val stringUri = imageUri.toString()
            val imgStr = if (stringUri.startsWith("http")) {
                if (imageFormatSetting == "base64") getBase64FromUrl(stringUri) else stringUri
            } else {
                if (imageFormatSetting == "url") {
                    _uiState.update { it.copy(isGenerating = false, error = "URL image format requires an online image URL. Local photos should use base64 or multipart.") }
                    return
                }
                getBase64FromUri(imageUri, economyMode)
            }
            
            if (imgStr == null) {
                _uiState.update { it.copy(isGenerating = false, error = "Failed to read selected photo. Please choose another image.") }
                return
            }
            
            JSONObject().apply {
                if (prompt.isNotBlank()) put("prompt", prompt)
                if (model.isNotBlank()) put("model", model)
                if (imageFormatSetting == "url") {
                     put("image_url", imgStr)
                } else {
                     put("image", imgStr)
                }
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
            
        val response = okHttpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        
        android.util.Log.d("StudioViewModel", "Edit Photo API response: ${response.code} $responseBody")
        
        handleImageResponse(response.code, responseBody)
    }
    
    fun saveMedia(context: android.content.Context, url: String, isVideo: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = if (url.startsWith("data:")) {
                    Base64.getDecoder().decode(url.substringAfter(","))
                } else {
                    val request = Request.Builder().url(url).build()
                    okHttpClient.newCall(request).execute().body?.bytes()
                }

                if (bytes != null) {
                    val resolver = context.contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        val timestamp = System.currentTimeMillis()
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "AI_Studio_$timestamp.${if (isVideo) "mp4" else "png"}")
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/png")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES)
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }

                    val collection = if (isVideo) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        } else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        } else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }

                    val itemUri = resolver.insert(collection, contentValues)
                    if (itemUri != null) {
                        resolver.openOutputStream(itemUri)?.use { out ->
                            out.write(bytes)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            contentValues.clear()
                            contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                            resolver.update(itemUri, contentValues, null, null)
                        }
                        launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Saved to Gallery", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                         launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Failed to save: Could not create MediaStore entry", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Failed to save: Could not fetch media data", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Failed to save: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleImageResponse(code: Int, body: String) {
        if (code in 200..299) {
            // Try to flexibly parse image url or base64 out of body
            val imgUrl = extractMediaUrl(body)
            if (imgUrl != null) {
                _uiState.update { it.copy(isGenerating = false, generatedMediaUrl = imgUrl) }
            } else {
                _uiState.update { it.copy(isGenerating = false, error = "Failed to extract image from response.") }
            }
        } else {
            val safeBody = if (body.length > 200) body.substring(0, 200) + "..." else body
            val errorMsg = when (code) {
                400 -> "Invalid request. Check model, prompt, or required image input.\nProvider output: $safeBody"
                401 -> "Invalid API key."
                402 -> "Provider credit or billing issue."
                404 -> "Endpoint not found. Check Base URL and Path in Settings."
                415 -> "Error $code: Unsupported Media Type. Check Request Format (JSON vs multipart) and endpoint.\nProvider output: $safeBody"
                422 -> "Image format not accepted by provider. Try multipart or base64 data URI.\nProvider output: $safeBody"
                429 -> "Rate limit exceeded."
                else -> "Error $code: $safeBody"
            }
            _uiState.update { it.copy(isGenerating = false, error = errorMsg) }
        }
    }
    
    private suspend fun generateVideo(prompt: String, imageUri: Uri?) {
        if (prompt.isBlank()) {
            _uiState.update { it.copy(isGenerating = false, error = "Please enter a prompt first.") }
            return
        }
        if (imageUri == null) {
             _uiState.update { it.copy(isGenerating = false, error = "Please select or generate a photo first.") }
             return
        }

        val baseUrl = settingsRepository.photoVideoBaseUrl.first().trimEnd('/')
        val createEndpoint = settingsRepository.photoVideoCreateEndpoint.first().removePrefix("/")
        val apiKey = settingsRepository.photoVideoApiKey.first()
        val model = settingsRepository.photoVideoModel.first()
        val format = settingsRepository.photoVideoFormat.first()
        val imageFormatSetting = settingsRepository.photoVideoImageFormat.first()
        val duration = settingsRepository.photoVideoDuration.first()
        val economyMode = settingsRepository.economyMode.first()
        val actualDuration = if (economyMode) "5" else duration

        if (baseUrl.isBlank() || createEndpoint.isBlank() || apiKey.isBlank()) {
            _uiState.update { it.copy(isGenerating = false, error = "Please configure Photo to Video Settings first.") }
            return
        }

        val url = "$baseUrl/$createEndpoint"
        android.util.Log.d("StudioViewModel", "Photo to Video URL: $url | Format: $format | ImageFormat: $imageFormatSetting")
        android.util.Log.d("StudioViewModel", "Photo to Video URI type: ${if (imageUri.toString().startsWith("http")) "http/https" else if (imageUri.toString().startsWith("content://")) "content://" else "file://"}")
        
        val requestBody = if (format == "multipart") {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            if (prompt.isNotBlank()) builder.addFormDataPart("prompt", prompt)
            if (model.isNotBlank()) builder.addFormDataPart("model", model)
            if (actualDuration.isNotBlank()) builder.addFormDataPart("duration", actualDuration)
            
            // Just try file directly, since generating Media URL gives http/https link that we'd have to download or use as url param.
            val stringUri = imageUri.toString()
            if (stringUri.startsWith("http")) { // Generated media url usually
                builder.addFormDataPart("image_url", stringUri)
            } else {
                val tempFile = getFileFromUri(imageUri, economyMode)
                if (tempFile != null) {
                    builder.addFormDataPart("image", "image.png", tempFile.asRequestBody("image/png".toMediaType()))
                }
            }
            builder.build()
        } else {
            val stringUri = imageUri.toString()
            val imgStr = if (stringUri.startsWith("http")) {
                if (imageFormatSetting == "base64") {
                    getBase64FromUrl(stringUri)
                } else {
                    stringUri
                }
            } else {
                if (imageFormatSetting == "url") {
                    _uiState.update { it.copy(isGenerating = false, error = "URL image format requires an online image URL. Local photos should use base64 or multipart.") }
                    return
                }
                getBase64FromUri(imageUri, economyMode)
            }
            
            if (imgStr == null) {
                _uiState.update { it.copy(isGenerating = false, error = "Failed to read selected photo. Please choose another image.") }
                return
            }
            
            JSONObject().apply {
                if (prompt.isNotBlank()) put("prompt", prompt)
                if (model.isNotBlank()) put("model", model)
                if (actualDuration.isNotBlank()) put("duration", actualDuration)
                
                if (baseUrl.contains("api.x.ai")) {
                    val imageObj = JSONObject().apply {
                        put("url", imgStr ?: "")
                    }
                    put("image", imageObj)
                } else {
                    if (imageFormatSetting == "url") {
                        put("image_url", imgStr ?: "")
                    } else {
                        put("image", imgStr ?: "")
                    }
                }
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
            
        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: ""
        
        android.util.Log.d("StudioViewModel", "Photo to Video API response: ${response.code} $body")
        
        if (response.code in 200..299) {
            // Check if it returned a video URL immediately
            val vidUrl = extractVideoUrl(body)
            if (vidUrl != null) {
                _uiState.update { it.copy(isGenerating = false, generatedVideoUrl = vidUrl) }
            } else {
                // Look for request ID and poll if status endpoint exists
                val reqId = extractRequestId(body)
                val statusEndpoint = settingsRepository.photoVideoStatusEndpoint.first().removePrefix("/")
                if (reqId != null && statusEndpoint.isNotBlank()) {
                    pollVideoStatus(reqId, apiKey, baseUrl, statusEndpoint)
                } else {
                    _uiState.update { it.copy(isGenerating = false, error = "Failed to extract Video URL or Request ID from response.") }
                }
            }
        } else {
            val safeBody = if (body.length > 200) body.substring(0, 200) + "..." else body
            val errorMsg = when (response.code) {
                400 -> "Invalid request. Check model, prompt, or required image input.\nProvider output: $safeBody"
                401 -> "Invalid API key."
                402 -> "Provider credit or billing issue."
                404 -> "Endpoint not found. Check Base URL and Path in Settings."
                415 -> "Error ${response.code}: Unsupported Media Type. Check Request Format (JSON vs multipart) and endpoint.\nProvider output: $safeBody"
                422 -> "Video request failed. The image payload format is not accepted. Try base64 data URI, URL, or multipart.\nProvider output: $safeBody"
                429 -> "Rate limit exceeded."
                else -> "Error ${response.code}: $safeBody"
            }
            _uiState.update { it.copy(isGenerating = false, error = errorMsg) }
        }
    }
    
    private suspend fun pollVideoStatus(reqId: String, apiKey: String, baseUrl: String, statusEndpoint: String) {
        var pollingUrl = "$baseUrl/$statusEndpoint"
            .replace("{id}", reqId)
            .replace("{request_id}", reqId)
            .replace("{task_id}", reqId)
            
        if (!statusEndpoint.contains("{") && !pollingUrl.endsWith("/$reqId")) {
            pollingUrl = "$pollingUrl/$reqId"
        }
        
        _uiState.update { it.copy(videoStatus = "Processing...") }
        
        val maxAttempts = 30
        var attempts = 0
        while (attempts < maxAttempts) {
            delay(5000)
            attempts++
            
            try {
                val request = Request.Builder()
                    .url(pollingUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                    
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                
                if (response.code in 200..299) {
                    val status = extractStatus(body)
                    
                    if (status == "completed" || status == "success") {
                        val vidUrl = extractVideoUrl(body) // from status or result if combined
                        if (vidUrl != null) {
                            _uiState.update { it.copy(isGenerating = false, generatedVideoUrl = vidUrl, videoStatus = null) }
                            return
                        } else {
                            // fetch result endpoint if separated
                            val resultEndpoint = settingsRepository.photoVideoResultEndpoint.first().removePrefix("/")
                            if (resultEndpoint.isNotBlank()) {
                                fetchVideoResult(reqId, apiKey, baseUrl, resultEndpoint)
                                return
                            } else {
                                _uiState.update { it.copy(isGenerating = false, error = "Completed but no Video URL found", videoStatus = null) }
                                return
                            }
                        }
                    } else if (status == "failed" || status == "error") {
                        _uiState.update { it.copy(isGenerating = false, error = "Video generation failed.", videoStatus = null) }
                        return
                    } else {
                        // Pending / Processing
                        _uiState.update { it.copy(videoStatus = "Status: $status (Attempt $attempts)") }
                    }
                } else {
                    _uiState.update { it.copy(isGenerating = false, error = "Polling failed: ${response.code}", videoStatus = null) }
                    return
                }
            } catch (e: Exception) {
                // ignore and retry
            }
        }
        _uiState.update { it.copy(isGenerating = false, error = "Polling timeout.", videoStatus = null) }
    }
    
    private suspend fun fetchVideoResult(reqId: String, apiKey: String, baseUrl: String, resultEndpoint: String) {
        var resUrl = "$baseUrl/$resultEndpoint"
            .replace("{id}", reqId)
            .replace("{request_id}", reqId)
            .replace("{task_id}", reqId)
            
        if (!resultEndpoint.contains("{") && !resUrl.endsWith("/$reqId")) {
            resUrl = "$resUrl/$reqId"
        }
        
        try {
            val request = Request.Builder()
                .url(resUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            
            if (response.code in 200..299) {
                val vidUrl = extractVideoUrl(body)
                if (vidUrl != null) {
                    _uiState.update { it.copy(isGenerating = false, generatedVideoUrl = vidUrl, videoStatus = null) }
                } else {
                    _uiState.update { it.copy(isGenerating = false, error = "Could not find video URL in result.", videoStatus = null) }
                }
            } else {
                 _uiState.update { it.copy(isGenerating = false, error = "Failed to fetch result: ${response.code}", videoStatus = null) }
            }
        } catch(e: Exception) {
             _uiState.update { it.copy(isGenerating = false, error = "Failed to fetch result: ${e.localizedMessage}", videoStatus = null) }
        }
    }

    // Flexible parsers
    private fun extractMediaUrl(jsonStr: String): String? {
        return try {
            val obj = JSONObject(jsonStr)
            
            // Common paths: URL in data array
            if (obj.has("data") && obj.optJSONArray("data") != null) {
                val arr = obj.getJSONArray("data")
                if (arr.length() > 0) {
                    val item = arr.getJSONObject(0)
                    if (item.has("url")) return item.getString("url")
                    if (item.has("b64_json")) return "data:image/png;base64,${item.getString("b64_json")}"
                }
            }
            // URL at root
            if (obj.has("url")) return obj.getString("url")
            if (obj.has("output") && obj.optJSONArray("output") != null) return obj.getJSONArray("output").getString(0)
            if (obj.has("image_url")) return obj.getString("image_url")
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractVideoUrl(jsonStr: String): String? {
        return try {
            val obj = JSONObject(jsonStr)
            // Luma structure roughly: { "state": "completed", "assets": {"video": "url"} }
            if (obj.has("assets")) {
                val assets = obj.getJSONObject("assets")
                if (assets.has("video")) return assets.getString("video")
            }
            // Runway structure roughly: { "taskTitle": "...", "output": ["url"] }
            if (obj.has("output") && obj.optJSONArray("output") != null) {
                return obj.getJSONArray("output").getString(0)
            }
            if (obj.has("video_url")) return obj.getString("video_url")
            
            val url = extractMediaUrl(jsonStr)
            if (url != null && url.endsWith(".mp4")) return url
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractRequestId(jsonStr: String): String? {
        return try {
            val obj = JSONObject(jsonStr)
            if (obj.has("id")) return obj.getString("id")
            if (obj.has("task_id")) return obj.getString("task_id")
            if (obj.has("request_id")) return obj.getString("request_id")
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractStatus(jsonStr: String): String? {
        return try {
            val obj = JSONObject(jsonStr)
            if (obj.has("state")) return obj.getString("state").lowercase()
            if (obj.has("status")) return obj.getString("status").lowercase()
            null
        } catch (e: Exception) {
            null
        }
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val okHttpClient: OkHttpClient,
        private val context: android.content.Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StudioViewModel(settingsRepository, okHttpClient, context) as T
        }
    }
}
