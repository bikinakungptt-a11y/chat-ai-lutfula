package com.example.di

import android.content.Context
import com.example.data.SettingsRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object AppContainer {
    private var _settingsRepository: SettingsRepository? = null
    
    fun getSettingsRepository(context: Context): SettingsRepository {
        if (_settingsRepository == null) {
            _settingsRepository = SettingsRepository(context.applicationContext)
        }
        return _settingsRepository!!
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
