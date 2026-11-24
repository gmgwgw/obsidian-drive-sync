package com.obsidiandrivesync.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.obsidiandrivesync.MainActivity
import com.obsidiandrivesync.VaultSyncApplication
import com.obsidiandrivesync.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SyncQuickSettingsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val app = application as VaultSyncApplication
        scope.launch {
            val settings = app.container.preferencesRepository.getSettings()
            val authState = app.container.tokenStore.getSession()
            val ready = Environment.isExternalStorageManager() &&
                settings.isConfigured &&
                authState != null &&
                authState.refreshToken.isNotBlank()

            if (!ready) {
                openMainActivity()
                return@launch
            }

            if (!app.container.syncCoordinator.isSyncing.value) {
                app.container.syncScheduler.enqueueManualSync(SyncWorker.TRIGGER_TILE)
                updateTile(state = Tile.STATE_ACTIVE, subtitle = "同期開始...")
            }
        }
    }

    private fun refreshTileState() {
        val app = application as VaultSyncApplication
        scope.launch {
            val syncing = app.container.syncCoordinator.isSyncing.value
            val settings = app.container.preferencesRepository.getSettings()
            val configured = settings.isConfigured && Environment.isExternalStorageManager()

            when {
                syncing -> updateTile(Tile.STATE_ACTIVE, "同期中...")
                configured -> updateTile(Tile.STATE_INACTIVE, settings.driveFolderName.ifBlank { "タップで同期" })
                else -> updateTile(Tile.STATE_INACTIVE, "未設定")
            }
        }
    }

    private fun updateTile(state: Int, subtitle: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.subtitle = subtitle
        tile.updateTile()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
