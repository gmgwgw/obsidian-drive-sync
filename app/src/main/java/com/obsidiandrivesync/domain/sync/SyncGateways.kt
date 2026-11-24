package com.obsidiandrivesync.domain.sync

import com.obsidiandrivesync.domain.model.BaselineEntry
import com.obsidiandrivesync.domain.model.LocalFileItem
import com.obsidiandrivesync.domain.model.RemoteFileItem

data class RemoteTreeResult(
    val items: List<RemoteFileItem>,
    val skippedWarnings: List<String>
)

interface LocalVaultGateway {
    suspend fun listFiles(ignoreMatcher: IgnoreRuleMatcher): List<LocalFileItem>
    fun computeMd5(relativePath: String): String?
    fun getLocalItem(relativePath: String): LocalFileItem?
    suspend fun ensureDirectory(relativePath: String)
    suspend fun writeFileAtomically(relativePath: String, modifiedTimeMs: Long, writer: suspend (java.io.OutputStream) -> Unit)
    fun openInputStream(relativePath: String): java.io.InputStream
    suspend fun renameFile(fromRelativePath: String, toRelativePath: String): LocalFileItem
    suspend fun deleteItem(relativePath: String, isDirectory: Boolean, moveToTrash: Boolean)
    suspend fun purgeExpiredTrash(retentionDays: Int): Int
}

interface RemoteDriveGateway {
    suspend fun listFolderTree(rootFolderId: String, ignoreMatcher: IgnoreRuleMatcher): RemoteTreeResult
    suspend fun createFolder(parentId: String, name: String): RemoteFileItem
    suspend fun downloadFile(driveFileId: String, outputStream: java.io.OutputStream)
    suspend fun uploadNewFile(
        parentId: String,
        name: String,
        relativePath: String,
        localGateway: LocalVaultGateway,
        sizeBytes: Long,
        mimeType: String
    ): RemoteFileItem

    suspend fun updateFileContent(
        driveFileId: String,
        relativePath: String,
        localGateway: LocalVaultGateway,
        sizeBytes: Long,
        mimeType: String
    ): RemoteFileItem

    suspend fun trashFile(driveFileId: String)
}

interface BaselineStoreGateway {
    suspend fun getAllEntries(): List<BaselineEntry>
    suspend fun upsert(entry: BaselineEntry)
    suspend fun deleteByPath(relativePath: String)
}
