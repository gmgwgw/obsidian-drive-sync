package com.obsidiandrivesync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.obsidiandrivesync.AppContainer
import com.obsidiandrivesync.data.auth.AuthUiState
import com.obsidiandrivesync.data.local.LocalVaultStorage
import com.obsidiandrivesync.data.local.TrashStats
import com.obsidiandrivesync.data.prefs.SyncSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<SyncSettings> = container.preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSettings())

    val authState: StateFlow<AuthUiState> = container.oauthManager.authStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthUiState())

    val lastAuthError: StateFlow<String?> = container.oauthManager.lastAuthError

    private val _trashStats = MutableStateFlow(TrashStats(0, 0L))
    val trashStats: StateFlow<TrashStats> = _trashStats.asStateFlow()

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    fun refreshTrashStats() {
        viewModelScope.launch {
            val current = container.preferencesRepository.settingsFlow.first()
            if (current.localVaultPath.isBlank()) {
                _trashStats.value = TrashStats(0, 0L)
            } else {
                val storage = LocalVaultStorage(vaultRootPathProvider = { current.localVaultPath })
                _trashStats.value = storage.getTrashStats()
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            container.syncScheduler.cancelPeriodicSync()
            container.oauthManager.signOut()
            _statusToast.value = "Google アカウントからログアウトしました"
        }
    }

    fun setPeriodicInterval(minutes: Int) {
        viewModelScope.launch {
            container.preferencesRepository.setPeriodicIntervalMinutes(minutes)
            val updated = container.preferencesRepository.getSettings()
            container.syncScheduler.schedulePeriodicSync(updated)
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setWifiOnly(enabled)
            val updated = container.preferencesRepository.getSettings()
            container.syncScheduler.schedulePeriodicSync(updated)
        }
    }

    fun setChargingOnly(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setChargingOnly(enabled)
            val updated = container.preferencesRepository.getSettings()
            container.syncScheduler.schedulePeriodicSync(updated)
        }
    }

    fun setMoveToTrash(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setMoveRemoteDeletedToTrash(enabled)
        }
    }

    fun setTrashRetentionDays(days: Int) {
        viewModelScope.launch {
            container.preferencesRepository.setTrashRetentionDays(days)
        }
    }

    fun emptyTrashNow() {
        viewModelScope.launch {
            val current = container.preferencesRepository.getSettings()
            if (current.localVaultPath.isNotBlank()) {
                val storage = LocalVaultStorage(vaultRootPathProvider = { current.localVaultPath })
                val count = storage.emptyTrashNow()
                _trashStats.value = storage.getTrashStats()
                _statusToast.value = ".trash から $count 件のファイルを削除しました"
            }
        }
    }

    fun setDeletionGuard(enabled: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setDeletionGuardEnabled(enabled)
        }
    }

    fun saveIgnorePatterns(multilineText: String) {
        viewModelScope.launch {
            val list = multilineText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            container.preferencesRepository.setIgnorePatterns(list)
            _statusToast.value = "除外ルールを保存しました"
        }
    }

    fun resetIgnorePatterns() {
        viewModelScope.launch {
            container.preferencesRepository.resetIgnorePatternsToDefault()
            _statusToast.value = "除外ルールをデフォルトに戻しました"
        }
    }

    fun resetBaseline() {
        viewModelScope.launch {
            container.syncDao.clearAllEntries()
            _statusToast.value = "同期スナップショットをリセットしました"
        }
    }

    fun consumeToast() {
        _statusToast.value = null
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(container) as T
        }
    }
}
