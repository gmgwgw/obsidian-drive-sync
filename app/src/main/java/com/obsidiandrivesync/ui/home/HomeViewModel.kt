package com.obsidiandrivesync.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.obsidiandrivesync.AppContainer
import com.obsidiandrivesync.data.auth.AuthUiState
import com.obsidiandrivesync.data.db.ConflictEntity
import com.obsidiandrivesync.data.db.SyncLogEntity
import com.obsidiandrivesync.data.prefs.SyncSettings
import com.obsidiandrivesync.domain.model.SyncProgress
import com.obsidiandrivesync.worker.SyncWorker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<SyncSettings> = container.preferencesRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncSettings())

    val authState: StateFlow<AuthUiState> = container.oauthManager.authStateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthUiState())

    val syncProgress: StateFlow<SyncProgress> = container.syncCoordinator.progress

    val isSyncing: StateFlow<Boolean> = container.syncCoordinator.isSyncing

    val latestLog: StateFlow<SyncLogEntity?> = container.syncDao.observeLatestLog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unacknowledgedConflicts: StateFlow<List<ConflictEntity>> =
        container.syncDao.observeUnacknowledgedConflicts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startManualSync(allowLargeDeletionOnce: Boolean = false) {
        container.syncScheduler.enqueueManualSync(
            triggerSource = SyncWorker.TRIGGER_MANUAL,
            allowLargeDeletionOnce = allowLargeDeletionOnce
        )
    }

    fun cancelSync() {
        container.syncCoordinator.cancelCurrentSync()
        container.syncScheduler.cancelActiveSync()
    }

    fun acknowledgeConflict(id: Long) {
        viewModelScope.launch {
            container.syncDao.acknowledgeConflict(id)
        }
    }

    fun acknowledgeAllConflicts() {
        viewModelScope.launch {
            container.syncDao.acknowledgeAllConflicts()
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(container) as T
        }
    }
}
