package com.obsidiandrivesync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.obsidiandrivesync.data.prefs.SyncSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class SyncScheduler(private val context: Context) {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    fun enqueueManualSync(
        triggerSource: String = SyncWorker.TRIGGER_MANUAL,
        allowLargeDeletionOnce: Boolean = false
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    SyncWorker.KEY_TRIGGER_SOURCE to triggerSource,
                    SyncWorker.KEY_ALLOW_LARGE_DELETION_ONCE to allowLargeDeletionOnce
                )
            )
            .build()

        val policy = if (allowLargeDeletionOnce) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        workManager.enqueueUniqueWork(
            UNIQUE_MANUAL_SYNC_WORK,
            policy,
            request
        )
    }

    fun schedulePeriodicSync(settings: SyncSettings) {
        if (!settings.isConfigured || settings.periodicIntervalMinutes <= 0) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_SYNC_WORK)
            return
        }

        val intervalMinutes = settings.periodicIntervalMinutes.coerceAtLeast(15).toLong()
        val networkType = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(settings.chargingOnly)
            .build()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_TRIGGER_SOURCE to SyncWorker.TRIGGER_PERIODIC))
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelActiveSync() {
        workManager.cancelUniqueWork(UNIQUE_MANUAL_SYNC_WORK)
    }

    fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(UNIQUE_PERIODIC_SYNC_WORK)
    }

    fun observeManualWorkActive(): Flow<Boolean> {
        return workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_MANUAL_SYNC_WORK)
            .map { infos ->
                infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            }
    }

    companion object {
        const val UNIQUE_MANUAL_SYNC_WORK = "obsidian_drive_manual_sync"
        const val UNIQUE_PERIODIC_SYNC_WORK = "obsidian_drive_periodic_sync"
    }
}
