package com.obsidiandrivesync.ui.picker

import android.os.Environment
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.obsidiandrivesync.AppContainer
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFolderPickerScreen(
    container: AppContainer,
    onFolderSelected: () -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(storageRoot) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val subDirs = remember(currentDir, refreshTrigger) {
        currentDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    val hasObsidianConfig = remember(currentDir, refreshTrigger) {
        File(currentDir, ".obsidian").isDirectory
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentDir.name.ifBlank { "内部ストレージ" }) },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = currentDir.parentFile
                        if (currentDir != storageRoot && parent != null) {
                            currentDir = parent
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
                            container.preferencesRepository.setLocalVaultPath(currentDir.absolutePath)
                            onFolderSelected()
                        }
                    },
                    enabled = currentDir != storageRoot,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (currentDir == storageRoot) {
                            "保管庫用のフォルダを選択または作成してください"
                        } else {
                            "「${currentDir.name}」を保管庫に選択"
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
                text = "パス: ${currentDir.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasObsidianConfig) {
                Text(
                    text = "Obsidian 保管庫（.obsidian フォルダ）を検出しました",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            HorizontalDivider()

            if (subDirs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("サブフォルダがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(subDirs, key = { it.absolutePath }) { dir ->
                        val isVault = File(dir, ".obsidian").isDirectory
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { currentDir = dir }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(dir.name, style = MaterialTheme.typography.bodyLarge)
                                if (isVault) {
                                    Text(
                                        "Obsidian Vault",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新規フォルダを作成") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("フォルダ名") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val trimmed = folderName.trim()
                    if (trimmed.isNotEmpty()) {
                        val newDir = File(currentDir, trimmed)
                        if (newDir.mkdirs() || newDir.isDirectory) {
                            currentDir = newDir
                            refreshTrigger++
                        }
                        showCreateDialog = false
                    }
                }) {
                    Text("作成")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("キャンセル") }
            }
        )
    }
}
