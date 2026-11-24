package com.obsidiandrivesync.domain.sync

import com.obsidiandrivesync.domain.model.BaselineEntry
import com.obsidiandrivesync.domain.model.ConflictItem
import com.obsidiandrivesync.domain.model.RemoteFileItem
import com.obsidiandrivesync.domain.model.SyncAction
import com.obsidiandrivesync.domain.model.SyncConfig
import com.obsidiandrivesync.domain.model.SyncPhase
import com.obsidiandrivesync.domain.model.SyncProgress
import com.obsidiandrivesync.domain.model.SyncSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SyncExecutor(
    private val localGateway: LocalVaultGateway,
    private val remoteGateway: RemoteDriveGateway,
    private val baselineStore: BaselineStoreGateway,
    private val planner: SyncPlanner = SyncPlanner(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    suspend fun execute(
        config: SyncConfig,
        onProgress: (SyncProgress) -> Unit = {}
    ): SyncSummary {
        val startedAt = clockMs()
        val ignoreMatcher = IgnoreRuleMatcher(config.ignorePatterns)
        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        val conflicts = mutableListOf<ConflictItem>()
        val allWarnings = mutableListOf<String>()

        try {
            currentCoroutineContext().ensureActive()
            onProgress(SyncProgress(SyncPhase.SCANNING_LOCAL, message = "ローカル保管庫をスキャン中..."))
            val localItems = localGateway.listFiles(ignoreMatcher)

            currentCoroutineContext().ensureActive()
            onProgress(SyncProgress(SyncPhase.SCANNING_REMOTE, message = "Google Drive をスキャン中..."))
            val remoteTree = remoteGateway.listFolderTree(config.driveFolderId, ignoreMatcher)
            allWarnings += remoteTree.skippedWarnings

            currentCoroutineContext().ensureActive()
            onProgress(SyncProgress(SyncPhase.PLANNING, message = "変更内容を解析中..."))
            val baseline = baselineStore.getAllEntries()

            val plan = planner.plan(
                baseline = baseline,
                localItems = localItems,
                remoteItems = remoteTree.items,
                deletionGuardEnabled = config.deletionGuardEnabled,
                allowLargeDeletionOnce = config.allowLargeDeletionOnce,
                localMd5Provider = { path -> localGateway.computeMd5(path) }
            )
            allWarnings += plan.warnings

            if (plan.deletionGuardTriggered) {
                onProgress(
                    SyncProgress(
                        phase = SyncPhase.DELETION_GUARD_BLOCKED,
                        message = "大量削除検出により同期を中断しました（削除予定: ${plan.pendingDeletionCount}件）"
                    )
                )
                return SyncSummary(
                    startedAtMs = startedAt,
                    finishedAtMs = clockMs(),
                    status = SyncPhase.DELETION_GUARD_BLOCKED,
                    skippedWarnings = allWarnings,
                    pendingDeletionCount = plan.pendingDeletionCount,
                    totalBaselineFiles = plan.totalBaselineFiles,
                    errorMessage = "大量の削除（${plan.pendingDeletionCount}/${plan.totalBaselineFiles} 件）が検出されたため安全装置が作動しました。"
                )
            }

            // Track live folder relativePath -> driveFileId so nested uploads know their parent folder ID.
            val remoteFolderIds = mutableMapOf<String, String>()
            remoteFolderIds[""] = config.driveFolderId
            remoteTree.items.filter { it.isDirectory }.forEach { remoteFolderIds[it.relativePath] = it.driveFileId }

            val totalSteps = plan.actions.size

            plan.actions.forEachIndexed { index, action ->
                currentCoroutineContext().ensureActive()
                onProgress(
                    SyncProgress(
                        phase = SyncPhase.EXECUTING,
                        currentStep = index + 1,
                        totalSteps = totalSteps,
                        currentPath = action.relativePath,
                        message = describeAction(action)
                    )
                )

                when (action) {
                    is SyncAction.CreateLocalDir -> {
                        localGateway.ensureDirectory(action.relativePath)
                        baselineStore.upsert(
                            BaselineEntry(
                                relativePath = action.relativePath,
                                driveFileId = action.remoteItem.driveFileId,
                                isDirectory = true,
                                md5Checksum = null,
                                localLastModifiedMs = 0L,
                                localSizeBytes = 0L,
                                remoteModifiedTimeMs = action.remoteItem.modifiedTimeMs,
                                lastSyncedAtMs = clockMs()
                            )
                        )
                    }

                    is SyncAction.CreateRemoteDir -> {
                        val parentId = ensureRemoteParentFolders(
                            relativePath = action.relativePath,
                            rootFolderId = config.driveFolderId,
                            remoteFolderIds = remoteFolderIds
                        )
                        val folderName = action.relativePath.substringAfterLast('/')
                        val created = remoteGateway.createFolder(parentId, folderName)
                        remoteFolderIds[action.relativePath] = created.driveFileId
                        baselineStore.upsert(
                            BaselineEntry(
                                relativePath = action.relativePath,
                                driveFileId = created.driveFileId,
                                isDirectory = true,
                                md5Checksum = null,
                                localLastModifiedMs = action.localItem.lastModifiedMs,
                                localSizeBytes = 0L,
                                remoteModifiedTimeMs = created.modifiedTimeMs,
                                lastSyncedAtMs = clockMs()
                            )
                        )
                    }

                    is SyncAction.UploadNew -> {
                        val parentId = ensureRemoteParentFolders(
                            relativePath = action.relativePath,
                            rootFolderId = config.driveFolderId,
                            remoteFolderIds = remoteFolderIds
                        )
                        val fileName = action.relativePath.substringAfterLast('/')
                        val currentLocal = localGateway.getLocalItem(action.relativePath) ?: action.localItem
                        val uploadedRemote = remoteGateway.uploadNewFile(
                            parentId = parentId,
                            name = fileName,
                            relativePath = action.relativePath,
                            localGateway = localGateway,
                            sizeBytes = currentLocal.sizeBytes,
                            mimeType = guessMimeType(fileName)
                        )
                        val localAfter = localGateway.getLocalItem(action.relativePath) ?: currentLocal
                        val md5 = uploadedRemote.md5Checksum ?: localGateway.computeMd5(action.relativePath)
                        baselineStore.upsert(
                            BaselineEntry(
                                relativePath = action.relativePath,
                                driveFileId = uploadedRemote.driveFileId,
                                isDirectory = false,
                                md5Checksum = md5,
                                localLastModifiedMs = localAfter.lastModifiedMs,
                                localSizeBytes = localAfter.sizeBytes,
                                remoteModifiedTimeMs = uploadedRemote.modifiedTimeMs,
                                lastSyncedAtMs = clockMs()
                            )
                        )
                        uploaded++
                    }

                    is SyncAction.UploadUpdate -> {
                        val fileName = action.relativePath.substringAfterLast('/')
                        val currentLocal = localGateway.getLocalItem(action.relativePath) ?: action.localItem
                        val updatedRemote = remoteGateway.updateFileContent(
                            driveFileId = action.driveFileId,
                            relativePath = action.relativePath,
                            localGateway = localGateway,
                            sizeBytes = currentLocal.sizeBytes,
                            mimeType = guessMimeType(fileName)
                        )
                        val localAfter = localGateway.getLocalItem(action.relativePath) ?: currentLocal
                        val md5 = updatedRemote.md5Checksum ?: localGateway.computeMd5(action.relativePath)
                        baselineStore.upsert(
                            BaselineEntry(
                                relativePath = action.relativePath,
                                driveFileId = updatedRemote.driveFileId,
                                isDirectory = false,
                                md5Checksum = md5,
                                localLastModifiedMs = localAfter.lastModifiedMs,
                                localSizeBytes = localAfter.sizeBytes,
                                remoteModifiedTimeMs = updatedRemote.modifiedTimeMs,
                                lastSyncedAtMs = clockMs()
                            )
                        )
                        uploaded++
                    }

                    is SyncAction.DownloadNew, is SyncAction.DownloadUpdate -> {
                        val remoteItem = when (action) {
                            is SyncAction.DownloadNew -> action.remoteItem
                            is SyncAction.DownloadUpdate -> action.remoteItem
                            else -> error("Unreachable")
                        }
                        downloadToLocalAndRecord(action.relativePath, remoteItem)
                        downloaded++
                    }

                    is SyncAction.ResolveConflict -> {
                        val now = clockMs()
                        val formattedTs = CONFLICT_TS_FORMATTER.format(Instant.ofEpochMilli(now))
                        var conflictPath = SyncPlanner.buildConflictPath(action.relativePath, formattedTs)
                        var suffix = 2
                        while (localGateway.getLocalItem(conflictPath) != null) {
                            conflictPath = SyncPlanner.buildConflictPath(action.relativePath, "$formattedTs ($suffix)")
                            suffix++
                        }

                        // 1. Preserve local edits under a conflict copy name
                        val conflictLocalItem = localGateway.renameFile(action.relativePath, conflictPath)

                        // 2. Download remote version to original path & update baseline
                        downloadToLocalAndRecord(action.relativePath, action.remoteItem)
                        downloaded++

                        // 3. Upload conflict copy to Drive & record baseline
                        val parentId = ensureRemoteParentFolders(
                            relativePath = conflictPath,
                            rootFolderId = config.driveFolderId,
                            remoteFolderIds = remoteFolderIds
                        )
                        val conflictFileName = conflictPath.substringAfterLast('/')
                        val uploadedConflict = remoteGateway.uploadNewFile(
                            parentId = parentId,
                            name = conflictFileName,
                            relativePath = conflictPath,
                            localGateway = localGateway,
                            sizeBytes = conflictLocalItem.sizeBytes,
                            mimeType = guessMimeType(conflictFileName)
                        )
                        val conflictMd5 = uploadedConflict.md5Checksum ?: localGateway.computeMd5(conflictPath)
                        baselineStore.upsert(
                            BaselineEntry(
                                relativePath = conflictPath,
                                driveFileId = uploadedConflict.driveFileId,
                                isDirectory = false,
                                md5Checksum = conflictMd5,
                                localLastModifiedMs = conflictLocalItem.lastModifiedMs,
                                localSizeBytes = conflictLocalItem.sizeBytes,
                                remoteModifiedTimeMs = uploadedConflict.modifiedTimeMs,
                                lastSyncedAtMs = clockMs()
                            )
                        )
                        uploaded++

                        conflicts += ConflictItem(
                            originalPath = action.relativePath,
                            conflictCopyPath = conflictPath,
                            timestampMs = now
                        )
                    }

                    is SyncAction.DeleteRemote -> {
                        remoteGateway.trashFile(action.driveFileId)
                        baselineStore.deleteByPath(action.relativePath)
                        if (!action.isDirectory) deletedRemote++
                    }

                    is SyncAction.DeleteLocal -> {
                        val deleted = localGateway.deleteItem(
                            relativePath = action.relativePath,
                            isDirectory = action.isDirectory,
                            moveToTrash = config.moveRemoteDeletedToTrash
                        )
                        if (deleted) {
                            baselineStore.deleteByPath(action.relativePath)
                            if (!action.isDirectory) deletedLocal++
                        }
                    }

                    is SyncAction.RecordBaselineOnly -> {
                        val md5 = action.remoteItem.md5Checksum ?: localGateway.computeMd5(action.relativePath)
                        baselineStore.upsert(
                            BaselineEntry(
                                relativePath = action.relativePath,
                                driveFileId = action.remoteItem.driveFileId,
                                isDirectory = action.localItem.isDirectory,
                                md5Checksum = md5,
                                localLastModifiedMs = action.localItem.lastModifiedMs,
                                localSizeBytes = action.localItem.sizeBytes,
                                remoteModifiedTimeMs = action.remoteItem.modifiedTimeMs,
                                lastSyncedAtMs = clockMs()
                            )
                        )
                    }

                    is SyncAction.CleanupBaseline -> {
                        baselineStore.deleteByPath(action.relativePath)
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            onProgress(SyncProgress(SyncPhase.CLEANING_TRASH, message = ".trash の期限切れファイルを整理中..."))
            val trashPurged = localGateway.purgeExpiredTrash(config.trashRetentionDays)

            val finishedAt = clockMs()
            onProgress(SyncProgress(SyncPhase.COMPLETED, totalSteps, totalSteps, message = "同期が完了しました"))
            return SyncSummary(
                startedAtMs = startedAt,
                finishedAtMs = finishedAt,
                status = SyncPhase.COMPLETED,
                uploadedCount = uploaded,
                downloadedCount = downloaded,
                deletedLocalCount = deletedLocal,
                deletedRemoteCount = deletedRemote,
                conflictCount = conflicts.size,
                trashPurgedCount = trashPurged,
                skippedWarnings = allWarnings,
                conflicts = conflicts
            )
        } catch (ce: CancellationException) {
            onProgress(SyncProgress(SyncPhase.CANCELLED, message = "同期がキャンセルされました"))
            throw ce
        } catch (e: Exception) {
            val phase = if (e is AuthExpiredException) SyncPhase.AUTH_EXPIRED else SyncPhase.FAILED
            onProgress(SyncProgress(phase, message = e.message ?: "同期エラーが発生しました"))
            return SyncSummary(
                startedAtMs = startedAt,
                finishedAtMs = clockMs(),
                status = phase,
                uploadedCount = uploaded,
                downloadedCount = downloaded,
                deletedLocalCount = deletedLocal,
                deletedRemoteCount = deletedRemote,
                conflictCount = conflicts.size,
                skippedWarnings = allWarnings,
                conflicts = conflicts,
                errorMessage = e.message ?: e::class.java.simpleName
            )
        }
    }

    private suspend fun downloadToLocalAndRecord(relativePath: String, remoteItem: RemoteFileItem) {
        remoteGateway.downloadFile(remoteItem.driveFileId) { inputStream ->
            localGateway.writeFileAtomically(relativePath, remoteItem.modifiedTimeMs) { output ->
                inputStream.copyTo(output, bufferSize = 32 * 1024)
                output.flush()
            }
        }
        val localAfter = localGateway.getLocalItem(relativePath)
        val md5 = remoteItem.md5Checksum ?: localGateway.computeMd5(relativePath)
        baselineStore.upsert(
            BaselineEntry(
                relativePath = relativePath,
                driveFileId = remoteItem.driveFileId,
                isDirectory = false,
                md5Checksum = md5,
                localLastModifiedMs = localAfter?.lastModifiedMs ?: remoteItem.modifiedTimeMs,
                localSizeBytes = localAfter?.sizeBytes ?: remoteItem.sizeBytes,
                remoteModifiedTimeMs = remoteItem.modifiedTimeMs,
                lastSyncedAtMs = clockMs()
            )
        )
    }

    private suspend fun ensureRemoteParentFolders(
        relativePath: String,
        rootFolderId: String,
        remoteFolderIds: MutableMap<String, String>
    ): String {
        val parentRel = relativePath.substringBeforeLast('/', "")
        if (parentRel.isEmpty()) return rootFolderId
        remoteFolderIds[parentRel]?.let { return it }

        val segments = parentRel.split('/')
        var currentPath = ""
        var currentParentId = rootFolderId
        for (segment in segments) {
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
            val existing = remoteFolderIds[currentPath]
            if (existing != null) {
                currentParentId = existing
            } else {
                val created = remoteGateway.createFolder(currentParentId, segment)
                remoteFolderIds[currentPath] = created.driveFileId
                currentParentId = created.driveFileId
                baselineStore.upsert(
                    BaselineEntry(
                        relativePath = currentPath,
                        driveFileId = created.driveFileId,
                        isDirectory = true,
                        md5Checksum = null,
                        localLastModifiedMs = 0L,
                        localSizeBytes = 0L,
                        remoteModifiedTimeMs = created.modifiedTimeMs,
                        lastSyncedAtMs = clockMs()
                    )
                )
            }
        }
        return currentParentId
    }

    private fun describeAction(action: SyncAction): String = when (action) {
        is SyncAction.CreateLocalDir -> "フォルダ作成 (ローカル): ${action.relativePath}"
        is SyncAction.CreateRemoteDir -> "フォルダ作成 (Drive): ${action.relativePath}"
        is SyncAction.UploadNew, is SyncAction.UploadUpdate -> "アップロード: ${action.relativePath}"
        is SyncAction.DownloadNew, is SyncAction.DownloadUpdate -> "ダウンロード: ${action.relativePath}"
        is SyncAction.DeleteLocal -> "削除 (ローカル): ${action.relativePath}"
        is SyncAction.DeleteRemote -> "ゴミ箱へ移動 (Drive): ${action.relativePath}"
        is SyncAction.ResolveConflict -> "競合解決: ${action.relativePath}"
        is SyncAction.RecordBaselineOnly -> "状態記録: ${action.relativePath}"
        is SyncAction.CleanupBaseline -> "履歴整理: ${action.relativePath}"
    }

    private fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "md", "markdown" -> "text/markdown"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "css" -> "text/css"
            "js" -> "application/javascript"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private val CONFLICT_TS_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss", Locale.US)
                .withZone(ZoneId.systemDefault())
    }
}

class AuthExpiredException(message: String) : Exception(message)
