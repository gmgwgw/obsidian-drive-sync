package com.obsidiandrivesync.sync

import android.content.ComponentName
import android.content.Context
import android.os.Environment
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import com.obsidiandrivesync.data.auth.GoogleOAuthManager
import com.obsidiandrivesync.data.db.ConflictEntity
import com.obsidiandrivesync.data.db.RoomBaselineStore
import com.obsidiandrivesync.data.db.SyncDao
import com.obsidiandrivesync.data.db.SyncLogEntity
import com.obsidiandrivesync.data.drive.GoogleDriveClient
import com.obsidiandrivesync.data.local.LocalVaultStorage
import com.obsidiandrivesync.data.prefs.SyncPreferencesRepository
import com.obsidiandrivesync.domain.model.SyncConfig
import com.obsidiandrivesync.domain.model.SyncPhase
import com.obsidiandrivesync.domain.model.SyncProgress
import com.obsidiandrivesync.domain.model.SyncSummary
import com.obsidiandrivesync.domain.sync.SyncExecutor
import com.obsidiandrivesync.tile.SyncQuickSettingsTileService
import com.obsidiandrivesync.widget.SyncAppWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class SyncCoordinator(
    private val context: Context,
    private val preferencesRepository: SyncPreferencesRepository,
    private val syncDao: SyncDao,
    private val oauthManager: GoogleOAuthManager,
    private val driveClient: GoogleDriveClient
) {
    private val syncMutex = Mutex()
    @Volatile
    private var activeSyncJob: Job? = null

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSummary = MutableStateFlow<SyncSummary?>(null)
    val lastSummary: StateFlow<SyncSummary?> = _lastSummary.asStateFlow()

    fun cancelCurrentSync() {
        activeSyncJob?.cancel()
    }

    suspend fun runSync(
        triggerSource: String,
        allowLargeDeletionOnce: Boolean = false
    ): SyncSummary {
        if (!syncMutex.tryLock()) {
            val now = System.currentTimeMillis()
            return SyncSummary(
                startedAtMs = now,
                finishedAtMs = now,
                status = SyncPhase.ALREADY_RUNNING,
                errorMessage = "別の同期が既に実行中です。"
            )
        }

        val runStartedAt = System.currentTimeMillis()
        _isSyncing.value = true
        notifySystemSurfaces()
        try {
            activeSyncJob = currentCoroutineContext()[Job]
            val settings = preferencesRepository.getSettings()

            if (!Environment.isExternalStorageManager()) {
                return recordFailure(
                    startedAt = runStartedAt,
                    triggerSource = triggerSource,
                    phase = SyncPhase.FAILED,
                    message = "「すべてのファイルへのアクセス」権限が許可されていません。"
                )
            }
            if (!settings.isConfigured) {
                return recordFailure(
                    startedAt = runStartedAt,
                    triggerSource = triggerSource,
                    phase = SyncPhase.FAILED,
                    message = "ローカル保管庫または Google Drive フォルダが未設定です。"
                )
            }

            // Ensure token is valid (or refresh) before starting scan
            oauthManager.getValidAccessToken(forceRefresh = false)

            val localStorage = LocalVaultStorage(vaultRootPathProvider = { settings.localVaultPath })
            val baselineStore = RoomBaselineStore(syncDao)
            val executor = SyncExecutor(
                localGateway = localStorage,
                remoteGateway = driveClient,
                baselineStore = baselineStore
            )

            val config = SyncConfig(
                localVaultPath = settings.localVaultPath,
                driveFolderId = settings.driveFolderId,
                driveFolderName = settings.driveFolderName,
                ignorePatterns = settings.ignorePatterns,
                moveRemoteDeletedToTrash = settings.moveRemoteDeletedToTrash,
                trashRetentionDays = settings.trashRetentionDays,
                deletionGuardEnabled = settings.deletionGuardEnabled,
                allowLargeDeletionOnce = allowLargeDeletionOnce
            )

            val summary = executor.execute(config) { prog ->
                _progress.value = prog
            }

            syncDao.insertLog(
                SyncLogEntity(
                    startedAtMs = summary.startedAtMs,
                    finishedAtMs = summary.finishedAtMs,
                    triggerSource = triggerSource,
                    status = summary.status.name,
                    uploadedCount = summary.uploadedCount,
                    downloadedCount = summary.downloadedCount,
                    deletedLocalCount = summary.deletedLocalCount,
                    deletedRemoteCount = summary.deletedRemoteCount,
                    conflictCount = summary.conflictCount,
                    trashPurgedCount = summary.trashPurgedCount,
                    warningsText = summary.skippedWarnings.joinToString("\n"),
                    errorMessage = summary.errorMessage
                )
            )
            syncDao.pruneOldLogs()

            if (summary.conflicts.isNotEmpty()) {
                syncDao.insertConflicts(
                    summary.conflicts.map {
                        ConflictEntity(
                            originalPath = it.originalPath,
                            conflictCopyPath = it.conflictCopyPath,
                            detectedAtMs = it.timestampMs,
                            acknowledged = false
                        )
                    }
                )
                syncDao.pruneOldAcknowledgedConflicts()
            }

            _lastSummary.value = summary
            return summary
        } catch (ce: CancellationException) {
            val now = System.currentTimeMillis()
            val cancelled = SyncSummary(startedAtMs = runStartedAt, finishedAtMs = now, status = SyncPhase.CANCELLED)
            _progress.value = SyncProgress(SyncPhase.CANCELLED, message = "同期がキャンセルされました")
            _lastSummary.value = cancelled
            withContext(NonCancellable) {
                syncDao.insertLog(
                    SyncLogEntity(
                        startedAtMs = runStartedAt,
                        finishedAtMs = now,
                        triggerSource = triggerSource,
                        status = SyncPhase.CANCELLED.name,
                        uploadedCount = 0,
                        downloadedCount = 0,
                        deletedLocalCount = 0,
                        deletedRemoteCount = 0,
                        conflictCount = 0,
                        trashPurgedCount = 0,
                        warningsText = "",
                        errorMessage = "ユーザーによりキャンセルされました"
                    )
                )
                syncDao.pruneOldLogs()
            }
            throw ce
        } catch (e: Exception) {
            val phase = if (e is com.obsidiandrivesync.domain.sync.AuthExpiredException) {
                SyncPhase.AUTH_EXPIRED
            } else {
                SyncPhase.FAILED
            }
            return recordFailure(
                startedAt = runStartedAt,
                triggerSource = triggerSource,
                phase = phase,
                message = e.message ?: e::class.java.simpleName
            )
        } finally {
            activeSyncJob = null
            _isSyncing.value = false
            syncMutex.unlock()
            withContext(NonCancellable) {
                notifySystemSurfaces()
            }
        }
    }

    private suspend fun recordFailure(
        startedAt: Long,
        triggerSource: String,
        phase: SyncPhase,
        message: String
    ): SyncSummary {
        _progress.value = SyncProgress(phase = phase, message = message)
        val summary = SyncSummary(
            startedAtMs = startedAt,
            finishedAtMs = System.currentTimeMillis(),
            status = phase,
            errorMessage = message
        )
        _lastSummary.value = summary
        syncDao.insertLog(
            SyncLogEntity(
                startedAtMs = summary.startedAtMs,
                finishedAtMs = summary.finishedAtMs,
                triggerSource = triggerSource,
                status = phase.name,
                uploadedCount = 0,
                downloadedCount = 0,
                deletedLocalCount = 0,
                deletedRemoteCount = 0,
                conflictCount = 0,
                trashPurgedCount = 0,
                warningsText = "",
                errorMessage = message
            )
        )
        return summary
    }

    private suspend fun notifySystemSurfaces() {
        runCatching {
            SyncAppWidget().updateAll(context)
        }
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, SyncQuickSettingsTileService::class.java)
            )
        }
    }
}
