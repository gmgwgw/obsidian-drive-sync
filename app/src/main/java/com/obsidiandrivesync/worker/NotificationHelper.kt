package com.obsidiandrivesync.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.obsidiandrivesync.MainActivity
import com.obsidiandrivesync.R
import com.obsidiandrivesync.domain.model.SyncPhase
import com.obsidiandrivesync.domain.model.SyncSummary

class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val statusChannel = NotificationChannel(
            CHANNEL_SYNC_STATUS,
            context.getString(R.string.notification_channel_sync_status),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val progressChannel = NotificationChannel(
            CHANNEL_SYNC_PROGRESS,
            context.getString(R.string.notification_channel_sync_progress),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannels(listOf(statusChannel, progressChannel))
    }

    fun buildExpeditedProgressNotification(): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, CHANNEL_SYNC_PROGRESS)
            .setSmallIcon(R.drawable.ic_sync_tile)
            .setContentTitle("VaultSync")
            .setContentText("Obsidian 保管庫を Google Drive と同期中...")
            .setOngoing(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()
    }

    fun notifySummaryIfNeeded(summary: SyncSummary) {
        if (!canPostNotifications()) return
        ensureChannels()

        when (summary.status) {
            SyncPhase.AUTH_EXPIRED -> {
                showAlert(
                    id = NOTIFY_AUTH_ID,
                    title = "Google 再ログインが必要です",
                    text = summary.errorMessage ?: "認証トークンの有効期限が切れました。アプリを開いてログインしてください。"
                )
            }
            SyncPhase.DELETION_GUARD_BLOCKED -> {
                showAlert(
                    id = NOTIFY_GUARD_ID,
                    title = "大量削除を検出し同期を中断しました",
                    text = "${summary.pendingDeletionCount} 件の削除が検出されました。アプリで確認してください。"
                )
            }
            SyncPhase.FAILED -> {
                showAlert(
                    id = NOTIFY_ERROR_ID,
                    title = "VaultSync 同期エラー",
                    text = summary.errorMessage ?: "同期中にエラーが発生しました。"
                )
            }
            SyncPhase.COMPLETED -> {
                if (summary.conflictCount > 0) {
                    showAlert(
                        id = NOTIFY_CONFLICT_ID,
                        title = "競合（コンフリクト）を検出しました (${summary.conflictCount}件)",
                        text = "両方の編集を保持するためコンフリクトコピーを作成しました。ホーム画面で確認できます。"
                    )
                }
            }
            else -> Unit
        }
    }

    private fun showAlert(id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_SYNC_STATUS)
            .setSmallIcon(R.drawable.ic_sync_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()
        notificationManager.notify(id, notification)
    }

    private fun mainActivityPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        const val CHANNEL_SYNC_STATUS = "vaultsync_status"
        const val CHANNEL_SYNC_PROGRESS = "vaultsync_progress"
        const val PROGRESS_NOTIFICATION_ID = 1001
        private const val NOTIFY_AUTH_ID = 1002
        private const val NOTIFY_GUARD_ID = 1003
        private const val NOTIFY_ERROR_ID = 1004
        private const val NOTIFY_CONFLICT_ID = 1005
    }
}
