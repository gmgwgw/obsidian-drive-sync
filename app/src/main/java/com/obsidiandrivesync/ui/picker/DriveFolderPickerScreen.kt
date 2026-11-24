package com.obsidiandrivesync.ui.picker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obsidiandrivesync.AppContainer
import com.obsidiandrivesync.data.drive.DriveFolderSummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveFolderPickerScreen(
    container: AppContainer,
    onFolderSelected: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val navStack = remember { mutableStateListOf(DriveFolderSummary("root", "マイドライブ")) }
    val currentFolder = navStack.last()

    var children by remember { mutableStateOf<List<DriveFolderSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    BackHandler(enabled = navStack.size > 1) {
        navStack.removeAt(navStack.lastIndex)
    }

    LaunchedEffect(currentFolder.id, refreshTrigger) {
        isLoading = true
        errorMessage = null
        runCatching {
            container.driveClient.listChildFolders(currentFolder.id)
        }.onSuccess { list ->
            children = list
        }.onFailure { err ->
            errorMessage = err.message ?: "フォルダ一覧の取得に失敗しました"
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentFolder.name) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (navStack.size > 1) {
                            navStack.removeAt(navStack.lastIndex)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "新規フォルダ")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val previous = container.preferencesRepository.getSettings()
                            val displayName = navStack.joinToString(" / ") { it.name }
                            if (previous.driveFolderId.isNotBlank() && previous.driveFolderId != currentFolder.id) {
                                container.syncDao.clearAllEntries()
                            }
                            container.preferencesRepository.setDriveFolder(currentFolder.id, displayName)
                            val updated = container.preferencesRepository.getSettings()
                            container.syncScheduler.schedulePeriodicSync(updated)
                            onFolderSelected()
                        }
                    },
                    enabled = currentFolder.id != "root",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (currentFolder.id == "root") {
                            "同期対象のフォルダを開いて選択してください"
                        } else {
                            "「${currentFolder.name}」を同期先に選択"
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "パス: ${navStack.joinToString(" / ") { it.name }}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null -> {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { refreshTrigger++ }) {
                            Text("再試行")
                        }
                    }
                }
                children.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("サブフォルダがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(children, key = { it.id }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { navStack.add(folder) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Drive に新規フォルダを作成") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("フォルダ名") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = folderName.trim()
                        if (trimmed.isNotEmpty()) {
                            showCreateDialog = false
                            scope.launch {
                                runCatching {
                                    container.driveClient.createFolder(currentFolder.id, trimmed)
                                }.onSuccess {
                                    refreshTrigger++
                                }.onFailure { err ->
                                    errorMessage = err.message ?: "フォルダの作成に失敗しました"
                                }
                            }
                        }
                    }
                ) {
                    Text("作成")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("キャンセル") }
            }
        )
    }
}
