package com.obsidiandrivesync.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.obsidiandrivesync.AppContainer
import com.obsidiandrivesync.ui.common.formatBytes
import com.obsidiandrivesync.ui.common.openAllFilesAccessSettings
import com.obsidiandrivesync.ui.common.rememberAllFilesAccessState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenLocalPicker: () -> Unit,
    onOpenDrivePicker: () -> Unit
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(container))
    val settings by vm.settings.collectAsStateWithLifecycle()
    val authState by vm.authState.collectAsStateWithLifecycle()
    val lastAuthError by vm.lastAuthError.collectAsStateWithLifecycle()
    val trashStats by vm.trashStats.collectAsStateWithLifecycle()
    val toast by vm.statusToast.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val hasStoragePermission = rememberAllFilesAccessState { vm.refreshTrashStats() }
    var showIgnoreDialog by remember { mutableStateOf(false) }
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var showResetBaselineDialog by remember { mutableStateOf(false) }

    LaunchedEffect(settings.localVaultPath, hasStoragePermission) {
        vm.refreshTrashStats()
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(toast!!)
            vm.consumeToast()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("設定") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Google Account
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Google アカウント", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (!authState.hasClientIdConfigured) {
                        Text(
                            "local.properties に GOOGLE_OAUTH_CLIENT_ID が未設定です。README を参照して設定後に再ビルドしてください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (!lastAuthError.isNullOrBlank()) {
                        Text(
                            text = lastAuthError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (authState.isSignedIn) {
                        Text("ログイン中: ${authState.accountEmail.ifBlank { "(メールアドレス未取得)" }}")
                        OutlinedButton(onClick = { vm.signOut() }) {
                            Text("ログアウト")
                        }
                    } else {
                        Text("Google Drive へアクセスするためログインが必要です。", style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = { scope.launch { container.oauthManager.launchSignIn(context) } },
                            enabled = authState.hasClientIdConfigured
                        ) {
                            Text("Google でログイン")
                        }
                    }
                }
            }

            // 2. Storage Permission & Folders
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ストレージと同期フォルダ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("すべてのファイルへのアクセス", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (hasStoragePermission) "許可済み" else "未許可（設定が必要）",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasStoragePermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        if (!hasStoragePermission) {
                            Button(onClick = { openAllFilesAccessSettings(context) }) {
                                Text("許可する")
                            }
                        }
                    }

                    Text(
                        "ローカル Vault: ${settings.localVaultPath.ifBlank { "未選択" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = onOpenLocalPicker,
                        enabled = hasStoragePermission,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ローカル保管庫フォルダを選択")
                    }

                    Text(
                        "Google Drive: ${settings.driveFolderName.ifBlank { "未選択" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = onOpenDrivePicker,
                        enabled = authState.isSignedIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Google Drive フォルダを選択")
                    }
                }
            }

            // 3. Background Schedule
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("定期バックグラウンド同期", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val intervals = listOf(0 to "オフ", 15 to "15分", 30 to "30分", 60 to "1時間", 180 to "3時間", 360 to "6時間", 720 to "12時間")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervals.forEach { (minutes, label) ->
                            FilterChip(
                                selected = settings.periodicIntervalMinutes == minutes,
                                onClick = { vm.setPeriodicInterval(minutes) },
                                label = { Text(label) }
                            )
                        }
                    }

                    SwitchRow(
                        title = "Wi-Fi 接続時のみ定期同期",
                        checked = settings.wifiOnly,
                        onCheckedChange = { vm.setWifiOnly(it) }
                    )
                    SwitchRow(
                        title = "充電中のみ定期同期",
                        checked = settings.chargingOnly,
                        onCheckedChange = { vm.setChargingOnly(it) }
                    )
                }
            }

            // 4. Deletion & .trash Retention Policy
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("削除と .trash ゴミ箱設定", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    SwitchRow(
                        title = "Drive 側の削除をローカル .trash/ へ移動",
                        subtitle = "オフにするとローカルファイルを完全削除します",
                        checked = settings.moveRemoteDeletedToTrash,
                        onCheckedChange = { vm.setMoveToTrash(it) }
                    )

                    Text(".trash/ の自動完全削除までの保持期間", style = MaterialTheme.typography.bodyMedium)
                    val retentions = listOf(7 to "7日", 14 to "14日", 30 to "30日", 90 to "90日", 0 to "自動削除しない")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        retentions.forEach { (days, label) ->
                            FilterChip(
                                selected = settings.trashRetentionDays == days,
                                onClick = { vm.setTrashRetentionDays(days) },
                                label = { Text(label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "現在の .trash: ${trashStats.fileCount} 件 (${formatBytes(trashStats.totalBytes)})",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = { showEmptyTrashDialog = true },
                            enabled = trashStats.fileCount > 0
                        ) {
                            Text("今すぐ空にする")
                        }
                    }

                    SwitchRow(
                        title = "大量削除ブレーキ（安全装置）",
                        subtitle = "既存ファイルの30%以上（10件以上）が削除対象なら自動中断",
                        checked = settings.deletionGuardEnabled,
                        onCheckedChange = { vm.setDeletionGuard(it) }
                    )
                }
            }

            // 5. Ignore Rules & Reset
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("除外ルールと同期状態", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "現在の除外パターン: ${settings.ignorePatterns.size} 件",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { showIgnoreDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("除外パターンを編集")
                    }
                    OutlinedButton(
                        onClick = { showResetBaselineDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("同期スナップショットをリセット")
                    }
                }
            }
        }
    }

    if (showIgnoreDialog) {
        var text by remember(settings.ignorePatterns) {
            mutableStateOf(settings.ignorePatterns.joinToString("\n"))
        }
        AlertDialog(
            onDismissRequest = { showIgnoreDialog = false },
            title = { Text("除外パターン（1行につき1ルール）") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        vm.resetIgnorePatterns()
                        showIgnoreDialog = false
                    }) {
                        Text("デフォルトに戻す")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.saveIgnorePatterns(text)
                    showIgnoreDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIgnoreDialog = false }) { Text("キャンセル") }
            }
        )
    }

    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = { Text(".trash を空にしますか？") },
            text = { Text("${trashStats.fileCount} 件のファイルが完全に削除され、復元できなくなります。") },
            confirmButton = {
                Button(onClick = {
                    vm.emptyTrashNow()
                    showEmptyTrashDialog = false
                }) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) { Text("キャンセル") }
            }
        )
    }

    if (showResetBaselineDialog) {
        AlertDialog(
            onDismissRequest = { showResetBaselineDialog = false },
            title = { Text("スナップショットをリセット") },
            text = {
                Text("前回同期記録を削除します。同期先フォルダを変更した場合に使用してください（ローカルと Drive の実ファイル自体は消えません）。")
            },
            confirmButton = {
                Button(onClick = {
                    vm.resetBaseline()
                    showResetBaselineDialog = false
                }) {
                    Text("リセット")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetBaselineDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
