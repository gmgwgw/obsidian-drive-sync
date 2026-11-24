package com.obsidiandrivesync.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.obsidiandrivesync.MainActivity
import com.obsidiandrivesync.VaultSyncApplication
import com.obsidiandrivesync.domain.model.SyncPhase
import com.obsidiandrivesync.ui.common.formatShortDateTime
import com.obsidiandrivesync.worker.SyncWorker

class SyncAppWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as VaultSyncApplication
        val isSyncing = app.container.syncCoordinator.isSyncing.value
        val latestLog = app.container.syncDao.getLatestLog()

        val statusText = when {
            isSyncing -> "同期中..."
            latestLog == null -> "未同期"
            latestLog.status == SyncPhase.COMPLETED.name -> {
                val timeStr = formatShortDateTime(latestLog.finishedAtMs)
                "最終: $timeStr (↑${latestLog.uploadedCount} ↓${latestLog.downloadedCount})"
            }
            else -> "前回: ${latestLog.status}"
        }

        provideContent {
            GlanceTheme {
                WidgetContent(statusText = statusText, isSyncing = isSyncing)
            }
        }
    }

    @Composable
    private fun WidgetContent(statusText: String, isSyncing: Boolean) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "VaultSync",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                )
                Text(
                    text = statusText,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp
                    ),
                    maxLines = 1
                )
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
            androidx.glance.Button(
                text = if (isSyncing) "同期中" else "今すぐ同期",
                enabled = !isSyncing,
                onClick = actionRunCallback<TriggerSyncWidgetAction>()
            )
        }
    }
}

class TriggerSyncWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val app = context.applicationContext as VaultSyncApplication
        app.container.syncScheduler.enqueueManualSync(SyncWorker.TRIGGER_WIDGET)
        SyncAppWidget().update(context, glanceId)
    }
}

class SyncAppWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SyncAppWidget()
}
