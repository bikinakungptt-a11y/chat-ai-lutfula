import java.net.URLEncoder

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.aichatmobile.xmqpr"
    minSdk = 26
    targetSdk = 36
    // APK versioning for future upgrades:
    // For every APK update, increase versionCode.
    // Example:
    // versionCode 1, versionName "1.0"
    // versionCode 2, versionName "1.1"
    // versionCode 3, versionName "1.2"
    // versionCode 4, versionName "2.0"
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    
    // Fallback if not configured in .env
    val signatureHashFallback = "p0faKYsmAJ1RKGOUaCxHLhlmMco="
    val rawSignatureHash = project.findProperty("MICROSOFT_SIGNATURE_HASH")?.toString()?.takeIf { it.isNotBlank() && it != "YOUR_BASE64_SIGNATURE_HASH" } ?: signatureHashFallback
    manifestPlaceholders["msalSignatureHash"] = rawSignatureHash
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

tasks.register("patchRealtimePriceLogic") {
  doLast {
    val file = file("src/main/java/com/example/ui/chat/ChatViewModel.kt")
    if (!file.exists()) return@doLast
    var current = file.readText()

    val searchStart = current.indexOf("    private fun shouldUseRealtimeSearch(messageText: String): Boolean {")
    val searchEnd = current.indexOf("    private suspend fun handleMemoryCommand", searchStart)
    if (searchStart >= 0 && searchEnd > searchStart) {
      val searchReplacement = """
    private fun shouldUseRealtimeSearch(messageText: String): Boolean {
        val textLower = messageText.lowercase().trim()

        val explicitSearchKeywords = listOf(
            "cari", "search", "carikan", "cek", "chek", "check",
            "berita", "news", "berita terbaru", "update terbaru",
            "hari ini", "sekarang", "live", "real time", "realtime",
            "viral", "trending", "positif", "negatif",
            "sentimen", "sentiment", "kenapa naik", "kenapa turun",
            "akan naik", "akan turun", "prediksi hari ini"
        )

        val cryptoKeywords = listOf(
            "btc", "bitcoin", "eth", "ethereum", "crypto", "usdt",
            "xrp", "sol", "solana", "bnb", "doge", "dogecoin",
            "ada", "cardano", "ton", "trx", "tron", "avax",
            "matic", "pol", "link", "ltc", "dot", "shib"
        )

        val goldKeywords = listOf("gold", "xau", "emas")

        val currencyKeywords = listOf(
            "mata uang", "kurs", "forex", "fx", "currency",
            "usd", "idr", "eur", "gbp", "jpy", "aud", "cad", "chf",
            "cny", "yuan", "myr", "thb", "php", "inr", "krw",
            "rub", "aed", "sar", "dollar", "dolar", "rupiah", "dxy"
        )

        if (cryptoKeywords.any { textLower.contains(it) }) return true
        if (goldKeywords.any { textLower.contains(it) }) return true
        if (currencyKeywords.any { textLower.contains(it) } && explicitSearchKeywords.any { textLower.contains(it) }) return true
        if (explicitSearchKeywords.any { textLower.contains(it) }) return true

        val currentDataKeywords = listOf(
            "cuaca", "weather", "jadwal", "schedule", "rilis terbaru",
            "subscription", "pricing", "harga paket",
            "status server", "down", "error hari ini"
        )
        return currentDataKeywords.any { textLower.contains(it) }
    }
      """.trimIndent() + "\n"
      current = current.substring(0, searchStart) + searchReplacement + current.substring(searchEnd)
    }

    val marker = """                if (handleMemoryCommand(messageText, sessionId)) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }
                
                val apiKey = settingsRepository.apiKey.first()"""

    if (current.contains(marker) && !current.contains("Checking BTC realtime price")) {
      val insert = """                if (handleMemoryCommand(messageText, sessionId)) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                if (imageUri == null && cryptoPriceRepository.isBtcUsdQuery(messageText)) {
                    _uiState.update { it.copy(loadingText = "Checking BTC realtime price...") }
                    val cryptoResult = cryptoPriceRepository.getBtcUsdPrice()
                    val price = cryptoResult.getOrNull()
                    if (price != null) {
                        val answer = cryptoPriceRepository.formatBtcUsdAnswer(price)
                        chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = answer))
                        _uiState.update { it.copy(isLoading = false, loadingText = null) }
                        return@launch
                    } else {
                        android.util.Log.w("ChatViewModel", "BTC realtime price failed: ${'$'}{cryptoResult.exceptionOrNull()?.message}")
                        _uiState.update { it.copy(loadingText = null) }
                    }
                }

                if (imageUri == null) {
                    val fiatQuery = fiatRateRepository.parseFiatRateQuery(messageText)
                    if (fiatQuery != null) {
                        _uiState.update { it.copy(loadingText = "Checking realtime rate...") }
                        val fiatResult = fiatRateRepository.getLatestRate(fiatQuery)
                        val rate = fiatResult.getOrNull()
                        if (rate != null) {
                            val answer = fiatRateRepository.formatFiatRateAnswer(rate)
                            chatRepository.insertMessage(MessageEntity(sessionId = sessionId, role = "assistant", content = answer))
                            _uiState.update { it.copy(isLoading = false, loadingText = null) }
                            return@launch
                        } else {
                            android.util.Log.w("ChatViewModel", "Realtime rate failed: ${'$'}{fiatResult.exceptionOrNull()?.message}")
                            _uiState.update { it.copy(loadingText = null) }
                        }
                    }
                }
                
                val apiKey = settingsRepository.apiKey.first()"""
      current = current.replace(marker, insert)
    }

    file.writeText(current)
  }
}

tasks.matching { it.name == "preBuild" }.configureEach {
  dependsOn("patchRealtimePriceLogic")
}

tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.converter.gson)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation("androidx.media3:media3-exoplayer:1.4.1")
  implementation("androidx.media3:media3-ui:1.4.1")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation(libs.msal)
  implementation(libs.mlkit.language.id)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
