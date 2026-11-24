package com.obsidiandrivesync.ui.home

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiandrivesync.AppContainer
import com.obsidiandrivesync.domain.model.SyncPhase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onNavigateToSettings: () -> Unit
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory(container))
    val settings by vm.settings.collectAsStateWithLifecycle()
    val authState by vm.authState.collectAsStateWithLifecycle()
    val progress by vm.syncProgress.collectAsStateWithLifecycle()
    val isSyncing by vm.isSyncing.collectAsStateWithLifecycle()
    val latestLog by vm.latestLog.collectAsStateWithLifecycle()
    val conflicts by vm.unacknowledgedConflicts.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasStoragePermission by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isReadyToSync = hasStoragePermission && authState.isSignedIn && settings.isConfigured

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VaultSync") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isReadyToSync) {
                SetupChecklistCard(
                    hasStoragePermission = hasStoragePermission,
                    isSignedIn = authState.isSignedIn,
                    hasLocalFolder = settings.localVaultPath.isNotBlank(),
                    hasDriveFolder = settings.driveFolderId.isNotBlank(),
                    onGrantStorage = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onOpenSettings = onNavigateToSettings
                )
            }

            if (latestLog?.status == SyncPhase.AUTH_EXPIRED.name) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Google 認証の再ログインが必要です",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            latestLog?.errorMessage ?: "リフレッシュトークンが失効しました。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch { container.oauthManager.launchSignIn(context) }
                        }) {
                            Text("Google に再ログイン")
                        }
                    }
                }
            }

            if (latestLog?.status == SyncPhase.DELETION_GUARD_BLOCKED.name && !isSyncing) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "大量削除ブレーキが作動しました",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            latestLog?.errorMessage ?: "多数のファイル削除が検出されたため同期を中断しました。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { vm.startManualSync(allowLargeDeletionOnce = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("今回の削除を許可して同期を実行")
                        }
                    }
                }
            }

            // Main Sync Status & Action Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "同期ステータス",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "ローカル: ${settings.localVaultPath.ifBlank { "未選択" }}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Google Drive: ${settings.driveFolderName.ifBlank { "未選択" }}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val scheduleText = if (settings.periodicIntervalMinutes <= 0) {
                        "定期同期: オフ（手動のみ）"
                    } else {
                        val net = if (settings.wifiOnly) "Wi-Fi限定" else "全通信"
                        "定期同期: ${settings.periodicIntervalMinutes}分ごと ($net)"
                    }
                    Text(
                        text = scheduleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isSyncing) {
                        Spacer(Modifier.height(4.dp))
                        if (progress.totalSteps > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (progress.currentStep.toFloat() / progress.totalSteps.toFloat()).coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "${progress.currentStep} / ${progress.totalSteps}: ${progress.message}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = progress.message.ifBlank { "同期を準備中..." },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else if (latestLog != null) {
                        val log = latestLog!!
                        val timeStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
                            .format(Date(log.finishedAtMs))
                        Text(
                            text = "最終同期: $timeStr (${formatStatusLabel(log.status)})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "↑ ${log.uploadedCount}  ↓ ${log.downloadedCount}  " +
                                "ローカル削除 ${log.deletedLocalCount}  Drive削除 ${log.deletedRemoteCount}  " +
                                "競合 ${log.conflictCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (log.trashPurgedCount > 0) {
                            Text(
                                text = ".trash 自動削除: ${log.trashPurgedCount} 件",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = "まだ同期は実行されていません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    if (isSyncing) {
                        OutlinedButton(
                            onClick = { vm.cancelSync() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("同期をキャンセル")
                        }
                    } else {
                        Button(
                            onClick = { vm.startManualSync() },
                            enabled = isReadyToSync,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("今すぐ同期")
                        }
                    }
                }
            }

            // Unacknowledged Conflicts Card
            if (conflicts.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "未確認の競合 (${conflicts.size}件)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = { vm.acknowledgeAllConflicts() }) {
                                Text("すべて確認済み")
                            }
                        }
                        Text(
                            text = "両方の編集を保持するため、ローカル変更をコンフリクトコピーとして保存しました。Obsidian 上で内容を確認・統合してください。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        conflicts.forEach { conflict ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "元: ${conflict.originalPath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "複製: ${conflict.conflictCopyPath}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(onClick = { vm.acknowledgeConflict(conflict.id) }) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "確認済みにする")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupChecklistCard(
    hasStoragePermission: Boolean,
    isSignedIn: Boolean,
    hasLocalFolder: Boolean,
    hasDriveFolder: Boolean,
    onGrantStorage: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "初期セットアップを完了してください",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            ChecklistRow("1. すべてのファイルへのアクセス権限", hasStoragePermission)
            ChecklistRow("2. Google アカウント連携", isSignedIn)
            ChecklistRow("3. ローカル Obsidian 保管庫フォルダ", hasLocalFolder)
            ChecklistRow("4. Google Drive 同期対象フォルダ", hasDriveFolder)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hasStoragePermission) {
                    Button(onClick = onGrantStorage) {
                        Text("権限を許可")
                    }
                }
                OutlinedButton(onClick = onOpenSettings) {
                    Text("設定画面へ")
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(label: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (done) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

fun formatStatusLabel(statusName: String): String = when (statusName) {
    SyncPhase.COMPLETED.name -> "成功"
    SyncPhase.CANCELLED.name -> "キャンセル"
    SyncPhase.DELETION_GUARD_BLOCKED.name -> "削除ブレーキ停止"
    SyncPhase.AUTH_EXPIRED.name -> "認証期限切れ"
    SyncPhase.FAILED.name -> "失敗"
    else -> statusName
}
