package com.obsidiandrivesync.ui.logs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.obsidiandrivesync.AppContainer
import com.obsidiandrivesync.data.db.SyncLogEntity
import com.obsidiandrivesync.domain.model.SyncPhase
import com.obsidiandrivesync.ui.home.formatStatusLabel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogsScreen(container: AppContainer) {
    val logs by container.syncDao.observeRecentLogs()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var selectedLog by remember { mutableStateOf<SyncLogEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同期履歴") },
                actions = {
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = { scope.launch { container.syncDao.clearLogs() } }) {
                            Text("履歴をクリア")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("同期履歴はまだありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs, key = { it.id }) { log ->
                    LogCard(log = log, onClick = { selectedLog = log })
                }
            }
        }
    }

    selectedLog?.let { log ->
        val timeStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
            .format(Date(log.finishedAtMs))
        val durationSec = ((log.finishedAtMs - log.startedAtMs).coerceAtLeast(0L)) / 1000.0
        AlertDialog(
            onDismissRequest = { selectedLog = null },
            title = { Text("同期詳細 (${formatStatusLabel(log.status)})") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("完了時刻: $timeStr", style = MaterialTheme.typography.bodySmall)
                    Text("トリガー: ${formatTriggerLabel(log.triggerSource)}", style = MaterialTheme.typography.bodySmall)
                    Text("所要時間: ${String.format(Locale.US, "%.1f", durationSec)} 秒", style = MaterialTheme.typography.bodySmall)
                    Text("アップロード: ${log.uploadedCount} 件", style = MaterialTheme.typography.bodySmall)
                    Text("ダウンロード: ${log.downloadedCount} 件", style = MaterialTheme.typography.bodySmall)
                    Text("ローカル削除: ${log.deletedLocalCount} 件", style = MaterialTheme.typography.bodySmall)
                    Text("Drive 削除: ${log.deletedRemoteCount} 件", style = MaterialTheme.typography.bodySmall)
                    Text("競合: ${log.conflictCount} 件", style = MaterialTheme.typography.bodySmall)
                    Text(".trash 自動削除: ${log.trashPurgedCount} 件", style = MaterialTheme.typography.bodySmall)
                    if (log.warningsText.isNotBlank()) {
                        Text("警告:\n${log.warningsText}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!log.errorMessage.isNullOrBlank()) {
                        Text(
                            "エラー: ${log.errorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLog = null }) { Text("閉じる") }
            }
        )
    }
}

@Composable
private fun LogCard(log: SyncLogEntity, onClick: () -> Unit) {
    val timeStr = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(log.finishedAtMs))
    val statusColor = when (log.status) {
        SyncPhase.COMPLETED.name -> MaterialTheme.colorScheme.primary
        SyncPhase.CANCELLED.name -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${formatStatusLabel(log.status)} (${formatTriggerLabel(log.triggerSource)})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
                Text(timeStr, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "↑ ${log.uploadedCount}  ↓ ${log.downloadedCount}  " +
                    "削除 ${log.deletedLocalCount + log.deletedRemoteCount}  " +
                    "競合 ${log.conflictCount}  .trash整理 ${log.trashPurgedCount}",
                style = MaterialTheme.typography.bodySmall
            )
            if (!log.errorMessage.isNullOrBlank()) {
                Text(
                    text = log.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }
        }
    }
}

private fun formatTriggerLabel(source: String): String = when (source) {
    "manual" -> "手動"
    "periodic" -> "定期"
    "tile" -> "クイックタイル"
    "widget" -> "ウィジェット"
    else -> source
}
