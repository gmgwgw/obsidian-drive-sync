package com.obsidiandrivesync.domain.sync

import com.obsidiandrivesync.domain.model.BaselineEntry
import com.obsidiandrivesync.domain.model.LocalFileItem
import com.obsidiandrivesync.domain.model.RemoteFileItem
import com.obsidiandrivesync.domain.model.SyncConfig
import com.obsidiandrivesync.domain.model.SyncPhase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

class SyncExecutorTest {

    @Test
    fun `conflict resolution keeps both versions and purges expired trash`() = runTest {
        val fixedNow = 1_795_486_530_000L // fixed timestamp
        val local = FakeLocalGateway().apply {
            files["Notes/Idea.md"] = FakeLocalEntry("local-edit".toByteArray(), 5_000L)
        }
        val remote = FakeRemoteGateway().apply {
            entries["Notes/Idea.md"] = FakeRemoteEntry(
                id = "file_1",
                parentId = "root",
                content = "remote-edit".toByteArray(),
                modifiedMs = 6_000L
            )
        }
        val baseline = FakeBaselineStore().apply {
            upsert(BaselineEntry("Notes/Idea.md", "file_1", false, md5("base"), 1_000L, 4L, 1_000L, 1_000L))
        }

        val executor = SyncExecutor(
            localGateway = local,
            remoteGateway = remote,
            baselineStore = baseline,
            clockMs = { fixedNow }
        )

        val summary = executor.execute(
            SyncConfig(
                localVaultPath = "/fake",
                driveFolderId = "root",
                driveFolderName = "Vault",
                ignorePatterns = IgnoreRuleMatcher.DEFAULT_PATTERNS,
                trashRetentionDays = 30
            )
        )

        assertEquals(SyncPhase.COMPLETED, summary.status)
        assertEquals(1, summary.conflictCount)
        assertEquals(1, summary.downloadedCount)
        assertEquals(1, summary.uploadedCount)
        assertEquals(30, local.lastPurgedRetentionDays)

        val conflictCopyPath = summary.conflicts.single().conflictCopyPath
        assertEquals("remote-edit", String(local.files["Notes/Idea.md"]!!.bytes))
        assertEquals("local-edit", String(local.files[conflictCopyPath]!!.bytes))
        assertTrue(remote.entries.containsKey(conflictCopyPath))
        assertEquals("local-edit", String(remote.entries[conflictCopyPath]!!.content))

        val storedPaths = baseline.getAllEntries().map { it.relativePath }.toSet()
        assertTrue(storedPaths.contains("Notes/Idea.md"))
        assertTrue(storedPaths.contains(conflictCopyPath))
    }

    @Test
    fun `creates nested remote folders before uploading nested file`() = runTest {
        val local = FakeLocalGateway().apply {
            dirs += "Projects"
            dirs += "Projects/Sub"
            files["Projects/Sub/plan.md"] = FakeLocalEntry("hello".toByteArray(), 2_000L)
        }
        val remote = FakeRemoteGateway()
        val baseline = FakeBaselineStore()

        val executor = SyncExecutor(local, remote, baseline, clockMs = { 5_000L })
        val summary = executor.execute(
            SyncConfig(
                localVaultPath = "/fake",
                driveFolderId = "root",
                driveFolderName = "Vault",
                ignorePatterns = IgnoreRuleMatcher.DEFAULT_PATTERNS
            )
        )

        assertEquals(SyncPhase.COMPLETED, summary.status)
        assertEquals(1, summary.uploadedCount)
        assertTrue(remote.createdFolders.contains("Projects"))
        assertTrue(remote.createdFolders.contains("Sub"))
        assertTrue(remote.entries.containsKey("Projects/Sub/plan.md"))
        val baselineMap = baseline.getAllEntries().associateBy { it.relativePath }
        assertTrue(baselineMap.containsKey("Projects"))
        assertTrue(baselineMap.containsKey("Projects/Sub"))
        assertTrue(baselineMap.containsKey("Projects/Sub/plan.md"))
    }

    @Test
    fun `deletes remote when local removed and moves local to trash when remote removed`() = runTest {
        val local = FakeLocalGateway().apply {
            files["keep_remote_deleted.md"] = FakeLocalEntry("same".toByteArray(), 1_000L)
        }
        val remote = FakeRemoteGateway().apply {
            entries["keep_local_deleted.md"] = FakeRemoteEntry("r_del", "root", "same".toByteArray(), 1_000L)
        }
        val baseline = FakeBaselineStore().apply {
            upsert(BaselineEntry("keep_remote_deleted.md", "r_gone", false, md5("same"), 1_000L, 4L, 1_000L, 1_000L))
            upsert(BaselineEntry("keep_local_deleted.md", "r_del", false, md5("same"), 1_000L, 4L, 1_000L, 1_000L))
        }

        val executor = SyncExecutor(local, remote, baseline, clockMs = { 9_000L })
        val summary = executor.execute(
            SyncConfig(
                localVaultPath = "/fake",
                driveFolderId = "root",
                driveFolderName = "Vault",
                ignorePatterns = IgnoreRuleMatcher.DEFAULT_PATTERNS,
                moveRemoteDeletedToTrash = true
            )
        )

        assertEquals(SyncPhase.COMPLETED, summary.status)
        assertEquals(1, summary.deletedLocalCount)
        assertEquals(1, summary.deletedRemoteCount)
        assertTrue(local.deletedToTrashPaths.contains("keep_remote_deleted.md"))
        assertTrue(remote.trashedIds.contains("r_del"))
        assertTrue(baseline.getAllEntries().isEmpty())
    }

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class FakeLocalEntry(var bytes: ByteArray, var modifiedMs: Long)
    private data class FakeRemoteEntry(val id: String, val parentId: String, val content: ByteArray, val modifiedMs: Long)

    private class FakeLocalGateway : LocalVaultGateway {
        val files = linkedMapOf<String, FakeLocalEntry>()
        val dirs = linkedSetOf<String>()
        val deletedToTrashPaths = mutableListOf<String>()
        var lastPurgedRetentionDays: Int? = null

        override suspend fun listFiles(ignoreMatcher: IgnoreRuleMatcher): List<LocalFileItem> {
            return dirs.map { LocalFileItem(it, true, 0L, 0L) } +
                files.map { (p, e) -> LocalFileItem(p, false, e.bytes.size.toLong(), e.modifiedMs) }
        }

        override fun computeMd5(relativePath: String): String? {
            val bytes = files[relativePath]?.bytes ?: return null
            return MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        }

        override fun getLocalItem(relativePath: String): LocalFileItem? {
            val entry = files[relativePath] ?: return null
            return LocalFileItem(relativePath, false, entry.bytes.size.toLong(), entry.modifiedMs)
        }

        override suspend fun ensureDirectory(relativePath: String) {
            dirs += relativePath
        }

        override suspend fun writeFileAtomically(
            relativePath: String,
            modifiedTimeMs: Long,
            writer: suspend (OutputStream) -> Unit
        ) {
            val out = ByteArrayOutputStream()
            writer(out)
            files[relativePath] = FakeLocalEntry(out.toByteArray(), modifiedTimeMs)
        }

        override fun openInputStream(relativePath: String): InputStream {
            return ByteArrayInputStream(files.getValue(relativePath).bytes)
        }

        override suspend fun renameFile(fromRelativePath: String, toRelativePath: String): LocalFileItem {
            val entry = files.remove(fromRelativePath)!!
            files[toRelativePath] = entry
            return LocalFileItem(toRelativePath, false, entry.bytes.size.toLong(), entry.modifiedMs)
        }

        override suspend fun deleteItem(relativePath: String, isDirectory: Boolean, moveToTrash: Boolean) {
            files.remove(relativePath)
            if (!isDirectory && moveToTrash) {
                deletedToTrashPaths += relativePath
            }
        }

        override suspend fun purgeExpiredTrash(retentionDays: Int): Int {
            lastPurgedRetentionDays = retentionDays
            return 2
        }
    }

    private class FakeRemoteGateway : RemoteDriveGateway {
        val entries = linkedMapOf<String, FakeRemoteEntry>()
        val createdFolders = mutableListOf<String>()
        val trashedIds = mutableListOf<String>()
        private var idSeq = 100

        override suspend fun listFolderTree(rootFolderId: String, ignoreMatcher: IgnoreRuleMatcher): RemoteTreeResult {
            val items = entries.map { (path, entry) ->
                val md5 = MessageDigest.getInstance("MD5").digest(entry.content).joinToString("") { "%02x".format(it) }
                RemoteFileItem(
                    driveFileId = entry.id,
                    parentId = entry.parentId,
                    name = path.substringAfterLast('/'),
                    relativePath = path,
                    isDirectory = false,
                    sizeBytes = entry.content.size.toLong(),
                    modifiedTimeMs = entry.modifiedMs,
                    md5Checksum = md5
                )
            }
            return RemoteTreeResult(items, emptyList())
        }

        override suspend fun createFolder(parentId: String, name: String): RemoteFileItem {
            val id = "dir_${++idSeq}"
            createdFolders += name
            return RemoteFileItem(id, parentId, name, name, true, 0L, 1_000L, null)
        }

        override suspend fun downloadFile(driveFileId: String, outputStream: OutputStream) {
            val entry = entries.values.first { it.id == driveFileId }
            outputStream.write(entry.content)
        }

        override suspend fun uploadNewFile(
            parentId: String,
            name: String,
            relativePath: String,
            localGateway: LocalVaultGateway,
            sizeBytes: Long,
            mimeType: String
        ): RemoteFileItem {
            val bytes = localGateway.openInputStream(relativePath).use { it.readBytes() }
            val id = "file_${++idSeq}"
            entries[relativePath] = FakeRemoteEntry(id, parentId, bytes, 10_000L)
            val md5 = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
            return RemoteFileItem(id, parentId, name, relativePath, false, bytes.size.toLong(), 10_000L, md5)
        }

        override suspend fun updateFileContent(
            driveFileId: String,
            relativePath: String,
            localGateway: LocalVaultGateway,
            sizeBytes: Long,
            mimeType: String
        ): RemoteFileItem {
            error("Not expected in this test")
        }

        override suspend fun trashFile(driveFileId: String) {
            trashedIds += driveFileId
            entries.entries.removeIf { it.value.id == driveFileId }
        }
    }

    private class FakeBaselineStore : BaselineStoreGateway {
        private val map = linkedMapOf<String, BaselineEntry>()
        override suspend fun getAllEntries(): List<BaselineEntry> = map.values.toList()
        override suspend fun upsert(entry: BaselineEntry) {
            map[entry.relativePath] = entry
        }
        override suspend fun deleteByPath(relativePath: String) {
            map.remove(relativePath)
        }
    }
}
