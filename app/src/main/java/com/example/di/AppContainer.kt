package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.AppDatabase
import com.example.data.ChatRepository
import com.example.data.MicrosoftAuthService
import com.example.data.MicrosoftGraphRepository
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
    private var _appwriteMemoryRepository: com.example.data.AppwriteMemoryRepository? = null
    private var _microsoftAuthService: MicrosoftAuthService? = null
    private var _microsoftGraphRepository: MicrosoftGraphRepository? = null

    fun getMicrosoftAuthService(context: Context): MicrosoftAuthService {
        if (_microsoftAuthService == null) {
            _microsoftAuthService = MicrosoftAuthService(context.applicationContext)
        }
        return _microsoftAuthService!!
    }

    fun getMicrosoftGraphRepository(context: Context): MicrosoftGraphRepository {
        if (_microsoftGraphRepository == null) {
            _microsoftGraphRepository = MicrosoftGraphRepository(getMicrosoftAuthService(context))
        }
        return _microsoftGraphRepository!!
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

    fun getAppwriteMemoryRepository(context: Context): com.example.data.AppwriteMemoryRepository {
        if (_appwriteMemoryRepository == null) {
            _appwriteMemoryRepository = com.example.data.AppwriteMemoryRepository(
                getSettingsRepository(context),
                okHttpClient
            )
        }
        return _appwriteMemoryRepository!!
    }

    fun getMemoryRepository(context: Context): com.example.data.MemoryRepository {
        if (_memoryRepository == null) {
            _memoryRepository = com.example.data.MemoryRepository(
                getDatabase(context).memoryDao(),
                getAppwriteMemoryRepository(context)
            )
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
