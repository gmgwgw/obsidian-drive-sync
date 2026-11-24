package com.obsidiandrivesync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.obsidiandrivesync.domain.model.SyncPhase
import com.obsidiandrivesync.sync.SyncCoordinator

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
    private val syncCoordinator: SyncCoordinator,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            NotificationHelper.PROGRESS_NOTIFICATION_ID,
            notificationHelper.buildExpeditedProgressNotification()
        )
    }

    override suspend fun doWork(): Result {
        val trigger = inputData.getString(KEY_TRIGGER_SOURCE) ?: TRIGGER_PERIODIC
        val allowLargeDeletionOnce = inputData.getBoolean(KEY_ALLOW_LARGE_DELETION_ONCE, false)

        val summary = syncCoordinator.runSync(
            triggerSource = trigger,
            allowLargeDeletionOnce = allowLargeDeletionOnce
        )

        notificationHelper.notifySummaryIfNeeded(summary)

        return when (summary.status) {
            SyncPhase.COMPLETED,
            SyncPhase.CANCELLED,
            SyncPhase.DELETION_GUARD_BLOCKED,
            SyncPhase.AUTH_EXPIRED -> Result.success()
            SyncPhase.FAILED -> {
                if (trigger == TRIGGER_PERIODIC && runAttemptCount < 2) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_TRIGGER_SOURCE = "trigger_source"
        const val KEY_ALLOW_LARGE_DELETION_ONCE = "allow_large_deletion_once"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_TILE = "tile"
        const val TRIGGER_WIDGET = "widget"
        const val TRIGGER_PERIODIC = "periodic"
    }
}
