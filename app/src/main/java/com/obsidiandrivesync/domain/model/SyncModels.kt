package com.obsidiandrivesync.domain.model

data class LocalFileItem(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

data class RemoteFileItem(
    val driveFileId: String,
    val parentId: String,
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedTimeMs: Long,
    val md5Checksum: String?
)

data class BaselineEntry(
    val relativePath: String,
    val driveFileId: String,
    val isDirectory: Boolean,
    val md5Checksum: String?,
    val localLastModifiedMs: Long,
    val localSizeBytes: Long,
    val remoteModifiedTimeMs: Long,
    val lastSyncedAtMs: Long
)

data class SyncConfig(
    val localVaultPath: String,
    val driveFolderId: String,
    val driveFolderName: String,
    val ignorePatterns: List<String>,
    val moveRemoteDeletedToTrash: Boolean = true,
    val trashRetentionDays: Int = 30,
    val deletionGuardEnabled: Boolean = true,
    val allowLargeDeletionOnce: Boolean = false
)

enum class SyncPhase {
    IDLE,
    SCANNING_LOCAL,
    SCANNING_REMOTE,
    PLANNING,
    EXECUTING,
    CLEANING_TRASH,
    COMPLETED,
    CANCELLED,
    ALREADY_RUNNING,
    DELETION_GUARD_BLOCKED,
    AUTH_EXPIRED,
    FAILED
}

data class SyncProgress(
    val phase: SyncPhase = SyncPhase.IDLE,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentPath: String = "",
    val message: String = ""
)

data class ConflictItem(
    val originalPath: String,
    val conflictCopyPath: String,
    val timestampMs: Long
)

data class SyncSummary(
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val status: SyncPhase,
    val uploadedCount: Int = 0,
    val downloadedCount: Int = 0,
    val deletedLocalCount: Int = 0,
    val deletedRemoteCount: Int = 0,
    val conflictCount: Int = 0,
    val trashPurgedCount: Int = 0,
    val skippedWarnings: List<String> = emptyList(),
    val conflicts: List<ConflictItem> = emptyList(),
    val errorMessage: String? = null,
    val pendingDeletionCount: Int = 0,
    val totalBaselineFiles: Int = 0
)

sealed interface SyncAction {
    val relativePath: String

    data class CreateLocalDir(
        override val relativePath: String,
        val remoteItem: RemoteFileItem
    ) : SyncAction

    data class CreateRemoteDir(
        override val relativePath: String,
        val localItem: LocalFileItem
    ) : SyncAction

    data class UploadNew(
        override val relativePath: String,
        val localItem: LocalFileItem
    ) : SyncAction

    data class UploadUpdate(
        override val relativePath: String,
        val localItem: LocalFileItem,
        val driveFileId: String
    ) : SyncAction

    data class DownloadNew(
        override val relativePath: String,
        val remoteItem: RemoteFileItem
    ) : SyncAction

    data class DownloadUpdate(
        override val relativePath: String,
        val remoteItem: RemoteFileItem
    ) : SyncAction

    data class DeleteRemote(
        override val relativePath: String,
        val driveFileId: String,
        val isDirectory: Boolean
    ) : SyncAction

    data class DeleteLocal(
        override val relativePath: String,
        val isDirectory: Boolean
    ) : SyncAction

    data class ResolveConflict(
        override val relativePath: String,
        val localItem: LocalFileItem,
        val remoteItem: RemoteFileItem
    ) : SyncAction

    data class RecordBaselineOnly(
        override val relativePath: String,
        val localItem: LocalFileItem,
        val remoteItem: RemoteFileItem
    ) : SyncAction

    data class CleanupBaseline(
        override val relativePath: String
    ) : SyncAction
}

data class SyncPlan(
    val actions: List<SyncAction>,
    val deletionGuardTriggered: Boolean = false,
    val pendingDeletionCount: Int = 0,
    val totalBaselineFiles: Int = 0,
    val warnings: List<String> = emptyList()
)
