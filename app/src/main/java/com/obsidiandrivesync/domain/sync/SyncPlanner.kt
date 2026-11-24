package com.obsidiandrivesync.domain.sync

import com.obsidiandrivesync.domain.model.BaselineEntry
import com.obsidiandrivesync.domain.model.LocalFileItem
import com.obsidiandrivesync.domain.model.RemoteFileItem
import com.obsidiandrivesync.domain.model.SyncAction
import com.obsidiandrivesync.domain.model.SyncPlan

/**
 * Pure 3-way sync planner comparing Baseline (last sync snapshot), Local, and Remote states.
 */
class SyncPlanner(
    private val timestampToleranceMs: Long = 2_000L,
    private val deletionGuardMinCount: Int = 10,
    private val deletionGuardRatio: Double = 0.30
) {

    fun plan(
        baseline: List<BaselineEntry>,
        localItems: List<LocalFileItem>,
        remoteItems: List<RemoteFileItem>,
        deletionGuardEnabled: Boolean,
        allowLargeDeletionOnce: Boolean,
        localMd5Provider: (String) -> String?
    ): SyncPlan {
        val baselineMap = baseline.associateBy { it.relativePath }
        val localMap = localItems.associateBy { it.relativePath }
        val remoteMap = remoteItems.associateBy { it.relativePath }

        val allPaths = (baselineMap.keys + localMap.keys + remoteMap.keys).toSortedSet()

        val dirCreateActions = mutableListOf<SyncAction>()
        val fileActions = mutableListOf<SyncAction>()
        val deleteFileActions = mutableListOf<SyncAction>()
        val deleteDirActions = mutableListOf<SyncAction>()
        val cleanupActions = mutableListOf<SyncAction>()

        for (path in allPaths) {
            val base = baselineMap[path]
            val local = localMap[path]
            val remote = remoteMap[path]

            val isDirectory = local?.isDirectory ?: remote?.isDirectory ?: base?.isDirectory ?: false

            if (isDirectory) {
                planDirectory(path, base, local, remote, dirCreateActions, deleteDirActions, cleanupActions)
            } else {
                planFile(
                    path = path,
                    base = base,
                    local = local,
                    remote = remote,
                    localMd5Provider = localMd5Provider,
                    fileActions = fileActions,
                    deleteFileActions = deleteFileActions,
                    cleanupActions = cleanupActions
                )
            }
        }

        // Order directory creation shallowest-first, deletions deepest-first.
        val sortedDirCreates = dirCreateActions.sortedBy { it.relativePath.count { ch -> ch == '/' } }
        val sortedDirDeletes = deleteDirActions.sortedByDescending { it.relativePath.count { ch -> ch == '/' } }

        val totalBaselineFiles = baseline.count { !it.isDirectory }
        val pendingDeletionCount = deleteFileActions.size

        val guardTriggered = deletionGuardEnabled &&
            !allowLargeDeletionOnce &&
            totalBaselineFiles >= deletionGuardMinCount &&
            pendingDeletionCount >= deletionGuardMinCount &&
            (pendingDeletionCount.toDouble() / totalBaselineFiles.toDouble()) >= deletionGuardRatio

        if (guardTriggered) {
            return SyncPlan(
                actions = emptyList(),
                deletionGuardTriggered = true,
                pendingDeletionCount = pendingDeletionCount,
                totalBaselineFiles = totalBaselineFiles
            )
        }

        val orderedActions = sortedDirCreates +
            fileActions +
            deleteFileActions +
            sortedDirDeletes +
            cleanupActions

        return SyncPlan(
            actions = orderedActions,
            deletionGuardTriggered = false,
            pendingDeletionCount = pendingDeletionCount,
            totalBaselineFiles = totalBaselineFiles
        )
    }

    private fun planDirectory(
        path: String,
        base: BaselineEntry?,
        local: LocalFileItem?,
        remote: RemoteFileItem?,
        dirCreateActions: MutableList<SyncAction>,
        deleteDirActions: MutableList<SyncAction>,
        cleanupActions: MutableList<SyncAction>
    ) {
        when {
            base == null && local != null && remote == null -> {
                dirCreateActions += SyncAction.CreateRemoteDir(path, local)
            }
            base == null && local == null && remote != null -> {
                dirCreateActions += SyncAction.CreateLocalDir(path, remote)
            }
            base == null && local != null && remote != null -> {
                dirCreateActions += SyncAction.RecordBaselineOnly(path, local, remote)
            }
            base != null && local != null && remote != null -> {
                // Both exist; no action needed unless metadata needs sync.
            }
            base != null && local == null && remote != null -> {
                deleteDirActions += SyncAction.DeleteRemote(path, remote.driveFileId, isDirectory = true)
            }
            base != null && local != null && remote == null -> {
                deleteDirActions += SyncAction.DeleteLocal(path, isDirectory = true)
            }
            base != null && local == null && remote == null -> {
                cleanupActions += SyncAction.CleanupBaseline(path)
            }
        }
    }

    private fun planFile(
        path: String,
        base: BaselineEntry?,
        local: LocalFileItem?,
        remote: RemoteFileItem?,
        localMd5Provider: (String) -> String?,
        fileActions: MutableList<SyncAction>,
        deleteFileActions: MutableList<SyncAction>,
        cleanupActions: MutableList<SyncAction>
    ) {
        when {
            // 1. Not in baseline
            base == null && local != null && remote == null -> {
                fileActions += SyncAction.UploadNew(path, local)
            }
            base == null && local == null && remote != null -> {
                fileActions += SyncAction.DownloadNew(path, remote)
            }
            base == null && local != null && remote != null -> {
                val localMd5 = localMd5Provider(path)
                if (localMd5 != null && remote.md5Checksum != null && localMd5.equals(remote.md5Checksum, ignoreCase = true)) {
                    fileActions += SyncAction.RecordBaselineOnly(path, local, remote)
                } else {
                    fileActions += SyncAction.ResolveConflict(path, local, remote)
                }
            }

            // 2. Both exist & in baseline
            base != null && local != null && remote != null -> {
                val localChanged = isLocalChanged(base, local, localMd5Provider)
                val remoteChanged = isRemoteChanged(base, remote)

                when {
                    !localChanged && !remoteChanged -> Unit // No-op
                    localChanged && !remoteChanged -> {
                        fileActions += SyncAction.UploadUpdate(path, local, remote.driveFileId)
                    }
                    !localChanged && remoteChanged -> {
                        fileActions += SyncAction.DownloadUpdate(path, remote)
                    }
                    else -> {
                        val localMd5 = localMd5Provider(path)
                        if (localMd5 != null && remote.md5Checksum != null && localMd5.equals(remote.md5Checksum, ignoreCase = true)) {
                            fileActions += SyncAction.RecordBaselineOnly(path, local, remote)
                        } else {
                            fileActions += SyncAction.ResolveConflict(path, local, remote)
                        }
                    }
                }
            }

            // 3. Deleted locally, still on remote
            base != null && local == null && remote != null -> {
                if (isRemoteChanged(base, remote)) {
                    // Remote was modified after baseline: preserve remote edit instead of deleting.
                    fileActions += SyncAction.DownloadNew(path, remote)
                } else {
                    deleteFileActions += SyncAction.DeleteRemote(path, remote.driveFileId, isDirectory = false)
                }
            }

            // 4. Deleted on remote, still exists locally
            base != null && local != null && remote == null -> {
                if (isLocalChanged(base, local, localMd5Provider)) {
                    // Local was modified after baseline: preserve local edit by re-uploading.
                    fileActions += SyncAction.UploadNew(path, local)
                } else {
                    deleteFileActions += SyncAction.DeleteLocal(path, isDirectory = false)
                }
            }

            // 5. Deleted on both sides
            base != null && local == null && remote == null -> {
                cleanupActions += SyncAction.CleanupBaseline(path)
            }
        }
    }

    private fun isLocalChanged(
        base: BaselineEntry,
        local: LocalFileItem,
        localMd5Provider: (String) -> String?
    ): Boolean {
        val sizeSame = local.sizeBytes == base.localSizeBytes
        val timeSame = kotlin.math.abs(local.lastModifiedMs - base.localLastModifiedMs) <= timestampToleranceMs
        if (sizeSame && timeSame) {
            return false
        }
        val baseMd5 = base.md5Checksum
        if (sizeSame && baseMd5 != null) {
            val currentMd5 = localMd5Provider(local.relativePath)
            if (currentMd5 != null && currentMd5.equals(baseMd5, ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    private fun isRemoteChanged(base: BaselineEntry, remote: RemoteFileItem): Boolean {
        if (base.driveFileId != remote.driveFileId) {
            return true
        }
        val baseMd5 = base.md5Checksum
        val remoteMd5 = remote.md5Checksum
        if (baseMd5 != null && remoteMd5 != null) {
            return !baseMd5.equals(remoteMd5, ignoreCase = true)
        }
        return kotlin.math.abs(remote.modifiedTimeMs - base.remoteModifiedTimeMs) > timestampToleranceMs ||
            remote.sizeBytes != base.localSizeBytes
    }

    companion object {
        fun buildConflictPath(originalPath: String, formattedTimestamp: String): String {
            val slashIdx = originalPath.lastIndexOf('/')
            val dir = if (slashIdx >= 0) originalPath.substring(0, slashIdx + 1) else ""
            val fileName = if (slashIdx >= 0) originalPath.substring(slashIdx + 1) else originalPath

            val dotIdx = fileName.lastIndexOf('.')
            val baseName = if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
            val ext = if (dotIdx > 0) fileName.substring(dotIdx) else ""

            return "${dir}${baseName} (conflict ${formattedTimestamp})${ext}"
        }
    }
}
