package com.example.ui.outlook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.GraphEmail
import com.example.data.MicrosoftGraphRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import com.microsoft.identity.client.IAccount
import com.example.data.MicrosoftAuthService

data class OutlookUiState(
    val emails: List<GraphEmail> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val microsoftAccount: IAccount? = null,
    val selectedFolder: String = "inbox"
)

class OutlookViewModel(
    private val graphRepository: MicrosoftGraphRepository,
    private val microsoftAuthService: MicrosoftAuthService
) : ViewModel() {

    private val _uiState = MutableStateFlow(OutlookUiState())
    val uiState: StateFlow<OutlookUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            microsoftAuthService.account.collectLatest { account ->
                _uiState.value = _uiState.value.copy(microsoftAccount = account)
                if (account != null && _uiState.value.emails.isEmpty()) {
                    loadEmails(_uiState.value.selectedFolder)
                }
            }
        }
        viewModelScope.launch {
            graphRepository.isLoading.collectLatest { loading ->
                _uiState.value = _uiState.value.copy(isLoading = loading)
            }
        }
        viewModelScope.launch {
            graphRepository.error.collectLatest { error ->
                _uiState.value = _uiState.value.copy(error = error)
            }
        }
        viewModelScope.launch {
            graphRepository.emails.collectLatest { emails ->
                _uiState.value = _uiState.value.copy(emails = emails)
            }
        }
    }

    fun loadEmails(folderId: String = "inbox") {
        _uiState.value = _uiState.value.copy(selectedFolder = folderId)
        viewModelScope.launch {
            graphRepository.loadLatestEmails(folderId)
        }
    }

    fun searchEmails(query: String) {
        if (query.isBlank()) {
            loadEmails(_uiState.value.selectedFolder)
            return
        }
        viewModelScope.launch {
            graphRepository.searchEmails(query)
        }
    }

    fun signInMicrosoft(activity: android.app.Activity) {
        viewModelScope.launch {
            microsoftAuthService.acquireTokenInteractive(activity)
        }
    }

    class Factory(
        private val graphRepository: MicrosoftGraphRepository,
        private val microsoftAuthService: MicrosoftAuthService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(OutlookViewModel::class.java)) {
                return OutlookViewModel(graphRepository, microsoftAuthService) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
