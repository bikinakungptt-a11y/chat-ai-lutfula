package com.example.data

import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

class MicrosoftAuthService(private val context: Context) {
    private var msalApp: ISingleAccountPublicClientApplication? = null
    private val localStorage = LocalStorage(context)

    private val _account = MutableStateFlow<IAccount?>(null)
    val account: StateFlow<IAccount?> = _account.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val scopes = arrayOf("User.Read", "Mail.Read", "offline_access")

    init {
        initializeMsal()
    }

    fun reinitializeMsal() {
        _account.value = null
        _authError.value = null
        msalApp = null
        initializeMsal()
    }

    fun isMsalAppInitialized(): Boolean {
        return msalApp != null
    }

    @Suppress("DEPRECATION")
    private fun getSignatureHash(): String {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            val signatures = info.signatures
            if (signatures != null) {
                for (signature in signatures) {
                    val md = java.security.MessageDigest.getInstance("SHA")
                    md.update(signature.toByteArray())
                    return android.util.Base64.encodeToString(md.digest(), android.util.Base64.NO_WRAP)
                }
            }
        } catch (e: Exception) {
            Log.e("MSAL", "Error getting signature hash", e)
        }
        return "p0faKYsmAJ1RKGOUaCxHLhlmMco=" // Fallback to debug keystore hash
    }

    private fun initializeMsal() {
        try {
            var clientId = localStorage.getMicrosoftClientId()
            
            if (clientId.isBlank() || clientId == "YOUR_MICROSOFT_CLIENT_ID") {
                clientId = BuildConfig.MICROSOFT_CLIENT_ID
                if (clientId.isBlank() || clientId == "YOUR_MICROSOFT_CLIENT_ID") {
                    _authError.value = "Client ID is empty. Please configure it in settings."
                    return // Cannot initialize without client ID
                }
            }
            
            val tenantId = localStorage.getMicrosoftTenant()

            val signatureHash = getSignatureHash()
            val encodedHash = java.net.URLEncoder.encode(signatureHash, "UTF-8")
            
            val msalConfigJson = """
            {
              "client_id" : "$clientId",
              "authorization_user_agent" : "DEFAULT",
              "redirect_uri" : "msauth://com.aistudio.aichatmobile.xmqpr/$encodedHash",
              "account_mode" : "SINGLE",
              "broker_redirect_uri_registered": true,
              "authorities" : [
                {
                  "type": "AAD",
                  "audience": {
                    "type": "AzureADandPersonalMicrosoftAccount",
                    "tenant_id": "$tenantId"
                  }
                }
              ]
            }
            """.trimIndent()

            val configFile = File(context.cacheDir, "auth_config_single_account.json")
            configFile.writeText(msalConfigJson)
            
            Log.i("MSAL", "MSAL init started")

            PublicClientApplication.createSingleAccountPublicClientApplication(
                context,
                configFile,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        Log.i("MSAL", "MSAL init success")
                        msalApp = application
                        _authError.value = null
                        loadAccount()
                    }

                    override fun onError(exception: MsalException) {
                        Log.e("MSAL", "Error creating MSAL app", exception)
                        _authError.value = "MSAL gagal diinisialisasi. Cek auth_config_single_account.json.\nError: ${exception.message}"
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MSAL", "Failed to initialize MSAL", e)
            _authError.value = "MSAL gagal diinisialisasi. Cek auth_config_single_account.json.\nConfig error: ${e.message}"
        }
    }

    private fun loadAccount() {
        msalApp?.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
            override fun onAccountLoaded(activeAccount: IAccount?) {
                _account.value = activeAccount
            }
            override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                _account.value = currentAccount
            }
            override fun onError(exception: MsalException) {
                Log.e("MSAL", "Error loading account", exception)
                _account.value = null
            }
        })
    }

    suspend fun acquireTokenInteractive(activity: Activity): Result<String> {
        val app = msalApp
        if (app == null) {
            val err = _authError.value ?: "MSAL app not initialized"
            return Result.failure(Exception(err))
        }
        
        Log.i("MSAL", "MSAL login started")
        return suspendCancellableCoroutine { continuation ->
            var resumed = false
            try {
                val parameters = AcquireTokenParameters.Builder()
                    .startAuthorizationFromActivity(activity)
                    .withScopes(scopes.toList())
                    .withCallback(object : AuthenticationCallback {
                        override fun onSuccess(authenticationResult: IAuthenticationResult) {
                            Log.i("MSAL", "MSAL login success")
                            _account.value = authenticationResult.account
                            if (!resumed) {
                                resumed = true
                                continuation.resume(Result.success(authenticationResult.accessToken))
                            }
                        }

                        override fun onError(exception: MsalException) {
                            val displayMsg = when {
                                exception.errorCode == "user_cancelled" -> "Login dibatalkan oleh pengguna."
                                exception.errorCode == "no_network_connection" -> "Gagal: Tidak ada koneksi internet."
                                exception.errorCode == "invalid_client" -> "Gagal: Client ID tidak valid."
                                exception.message?.contains("redirect_uri") == true -> "Gagal: Redirect URI mismatch. Pastikan Signature Hash sesuai."
                                else -> "Login gagal: ${exception.message}"
                            }
                            Log.e("MSAL", "MSAL login failed: $displayMsg", exception)
                            if (!resumed) {
                                resumed = true
                                continuation.resume(Result.failure(Exception(displayMsg)))
                            }
                        }

                        override fun onCancel() {
                            Log.i("MSAL", "MSAL login cancelled")
                            if (!resumed) {
                                resumed = true
                                continuation.resume(Result.failure(Exception("Login dibatalkan.")))
                            }
                        }
                    })
                    .build()

                app.acquireToken(parameters)
            } catch (e: Exception) {
                if (!resumed) {
                    resumed = true
                    continuation.resume(Result.failure(e))
                }
            }
        }
    }

    suspend fun acquireTokenSilent(): String? {
        val app = msalApp ?: return null
        val currentAccount = _account.value ?: return null
        Log.i("MSAL", "MSAL token silent started")
        return suspendCancellableCoroutine { continuation ->
            var resumed = false
            try {
                val parameters = AcquireTokenSilentParameters.Builder()
                    .fromAuthority(app.configuration.defaultAuthority.authorityURL.toString())
                    .withScopes(scopes.toList())
                    .forAccount(currentAccount)
                    .withCallback(object : SilentAuthenticationCallback {
                        override fun onSuccess(authenticationResult: IAuthenticationResult) {
                            if (!resumed) {
                                resumed = true
                                continuation.resume(authenticationResult.accessToken)
                            }
                        }

                        override fun onError(exception: MsalException) {
                            Log.e("MSAL", "Silent Auth failed", exception)
                            if (!resumed) {
                                resumed = true
                                continuation.resume(null)
                            }
                        }
                    })
                    .build()
                
                app.acquireTokenSilentAsync(parameters)
            } catch (e: Exception) {
                if (!resumed) {
                    resumed = true
                    continuation.resume(null)
                }
            }
        }
    }

    suspend fun signOut(): Boolean {
        val app = msalApp ?: return false
        return suspendCancellableCoroutine { continuation ->
            app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    _account.value = null
                    continuation.resume(true)
                }

                override fun onError(exception: MsalException) {
                    Log.e("MSAL", "Sign out failed", exception)
                    continuation.resume(false)
                }
            })
        }
    }
}

