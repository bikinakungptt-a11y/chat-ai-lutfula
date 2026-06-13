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
    private var msalApp: IMultipleAccountPublicClientApplication? = null
    private val localStorage = LocalStorage(context)

    private val _account = MutableStateFlow<IAccount?>(null)
    val account: StateFlow<IAccount?> = _account.asStateFlow()

    private val scopes = arrayOf("User.Read", "Mail.ReadBasic", "Mail.Read", "offline_access")

    init {
        initializeMsal()
    }

    fun reinitializeMsal() {
        _account.value = null
        msalApp = null
        initializeMsal()
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
        return "EfKLa/C+05Hz/xBbYz1eP6zecJ0=" // Fallback to debug keystore hash
    }

    private fun initializeMsal() {
        try {
            var clientId = localStorage.getMicrosoftClientId()
            
            if (clientId.isBlank() || clientId == "YOUR_MICROSOFT_CLIENT_ID") {
                clientId = BuildConfig.MICROSOFT_CLIENT_ID
                if (clientId.isBlank() || clientId == "YOUR_MICROSOFT_CLIENT_ID") {
                    clientId = "YOUR_MICROSOFT_CLIENT_ID" // Placeholder, expect user to provide
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
              "account_mode" : "MULTIPLE",
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

            val configFile = File(context.cacheDir, "msal_config.json")
            configFile.writeText(msalConfigJson)

            PublicClientApplication.createMultipleAccountPublicClientApplication(
                context,
                configFile,
                object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                    override fun onCreated(application: IMultipleAccountPublicClientApplication) {
                        msalApp = application
                        loadAccounts()
                    }

                    override fun onError(exception: MsalException) {
                        Log.e("MSAL", "Error creating MSAL app: ", exception)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("MSAL", "Failed to initialize MSAL", e)
        }
    }

    private fun loadAccounts() {
        msalApp?.getAccounts(object : IPublicClientApplication.LoadAccountsCallback {
            override fun onTaskCompleted(result: List<IAccount>) {
                if (result.isNotEmpty()) {
                    _account.value = result.first()
                } else {
                    _account.value = null
                }
            }

            override fun onError(exception: MsalException) {
                Log.e("MSAL", "Error loading accounts: ", exception)
                _account.value = null
            }
        })
    }

    suspend fun acquireTokenInteractive(activity: Activity): String? {
        val app = msalApp ?: return null
        return suspendCancellableCoroutine { continuation ->
            val parameters = AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .withScopes(scopes.toList())
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        _account.value = authenticationResult.account
                        continuation.resume(authenticationResult.accessToken)
                    }

                    override fun onError(exception: MsalException) {
                        Log.e("MSAL", "Interactive Auth failed", exception)
                        continuation.resume(null)
                    }

                    override fun onCancel() {
                        Log.i("MSAL", "Interactive Auth canceled")
                        continuation.resume(null)
                    }
                })
                .build()

            app.acquireToken(parameters)
        }
    }

    suspend fun acquireTokenSilent(): String? {
        val app = msalApp ?: return null
        val currentAccount = _account.value ?: return null
        return suspendCancellableCoroutine { continuation ->
            val parameters = AcquireTokenSilentParameters.Builder()
                .fromAuthority(app.configuration.defaultAuthority.authorityURL.toString())
                .withScopes(scopes.toList())
                .forAccount(currentAccount)
                .withCallback(object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        continuation.resume(authenticationResult.accessToken)
                    }

                    override fun onError(exception: MsalException) {
                        Log.e("MSAL", "Silent Auth failed", exception)
                        _account.value = null
                        continuation.resume(null)
                    }
                })
                .build()
            
            app.acquireTokenSilentAsync(parameters)
        }
    }

    suspend fun signOut(): Boolean {
        val app = msalApp ?: return false
        val currentAccount = _account.value ?: return true
        return suspendCancellableCoroutine { continuation ->
            app.removeAccount(currentAccount, object : IMultipleAccountPublicClientApplication.RemoveAccountCallback {
                override fun onRemoved() {
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
