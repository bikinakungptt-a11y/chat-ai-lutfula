package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelName: String = "",
    val firecrawlApiKey: String = "",
    val isSaved: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val url = settingsRepository.baseUrl.first()
            val key = settingsRepository.apiKey.first()
            val model = settingsRepository.model.first()
            val firecrawlKey = settingsRepository.firecrawlApiKey.first()
            _uiState.update {
                it.copy(
                    baseUrl = url,
                    apiKey = key,
                    modelName = model,
                    firecrawlApiKey = firecrawlKey
                )
            }
        }
    }

    fun updateBaseUrl(url: String) {
        _uiState.update { it.copy(baseUrl = url, isSaved = false) }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key, isSaved = false) }
    }

    fun updateModelName(model: String) {
        _uiState.update { it.copy(modelName = model, isSaved = false) }
    }
    
    fun updateFirecrawlApiKey(key: String) {
        _uiState.update { it.copy(firecrawlApiKey = key, isSaved = false) }
    }

    fun applyPreset(presetName: String) {
        val (url, model) = when (presetName) {
            "OpenAI" -> "https://api.openai.com/v1" to "gpt-4o"
            "OpenRouter" -> "https://openrouter.ai/api/v1" to "openai/o1-mini"
            "xAI" -> "https://api.x.ai/v1" to "grok-2-latest"
            else -> "" to ""
        }
        _uiState.update { 
            it.copy(
                baseUrl = url.takeIf { url.isNotEmpty() } ?: it.baseUrl,
                modelName = model.takeIf { model.isNotEmpty() } ?: it.modelName,
                isSaved = false
            )
        }
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsRepository.saveSettings(state.apiKey, state.baseUrl, state.modelName, state.firecrawlApiKey)
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    
    fun resetSaveState() {
        _uiState.update { it.copy(isSaved = false) }
    }

    class Factory(
        private val settingsRepository: SettingsRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(settingsRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
