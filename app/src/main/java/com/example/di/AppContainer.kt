package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.ChatRepository
import com.example.data.SettingsRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object AppContainer {
    private var _settingsRepository: SettingsRepository? = null
    private var _database: AppDatabase? = null
    private var _chatRepository: ChatRepository? = null
    private var _memoryRepository: com.example.data.MemoryRepository? = null
    private var _localStorage: com.example.data.LocalStorage? = null

    fun getLocalStorage(context: Context): com.example.data.LocalStorage {
        if (_localStorage == null) {
            _localStorage = com.example.data.LocalStorage(context.applicationContext)
        }
        return _localStorage!!
    }
    
    private fun getDatabase(context: Context): AppDatabase {
        if (_database == null) {
            _database = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "app_database"
            ).fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        }
        return _database!!
    }

    fun getSettingsRepository(context: Context): SettingsRepository {
        if (_settingsRepository == null) {
            _settingsRepository = SettingsRepository(context.applicationContext)
        }
        return _settingsRepository!!
    }
    
    fun getChatRepository(context: Context): ChatRepository {
        if (_chatRepository == null) {
            _chatRepository = ChatRepository(getDatabase(context).chatDao())
        }
        return _chatRepository!!
    }

    fun getMemoryRepository(context: Context): com.example.data.MemoryRepository {
        if (_memoryRepository == null) {
            _memoryRepository = com.example.data.MemoryRepository(getDatabase(context).memoryDao())
        }
        return _memoryRepository!!
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
