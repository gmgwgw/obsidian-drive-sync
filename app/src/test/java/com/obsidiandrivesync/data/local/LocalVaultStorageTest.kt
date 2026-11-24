package com.obsidiandrivesync.data.local

import com.obsidiandrivesync.domain.sync.IgnoreRuleMatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalVaultStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `atomic write, md5, japanese paths, and ignore listing work accurately`() = runTest {
        val vaultDir = tempFolder.newFolder("vault")
        var now = 1_700_000_000_000L
        val storage = LocalVaultStorage(
            vaultRootPathProvider = { vaultDir.absolutePath },
            clockMs = { now }
        )

        storage.writeFileAtomically("日記/2026年 ノート.md", 1_650_000_000_000L) { out ->
            out.write("こんにちは Obsidian".toByteArray(Charsets.UTF_8))
        }
        File(vaultDir, ".obsidian").mkdirs()
        File(vaultDir, ".obsidian/workspace.json").writeText("{}")
        File(vaultDir, ".obsidian/app.json").writeText("{}")

        val items = storage.listFiles(IgnoreRuleMatcher(IgnoreRuleMatcher.DEFAULT_PATTERNS))
        val paths = items.map { it.relativePath }.toSet()

        assertTrue(paths.contains("日記/2026年 ノート.md"))
        assertTrue(paths.contains(".obsidian/app.json"))
        assertFalse(paths.contains(".obsidian/workspace.json"))
        assertNotNull(storage.computeMd5("日記/2026年 ノート.md"))
        assertEquals(1_650_000_000_000L, File(vaultDir, "日記/2026年 ノート.md").lastModified())
    }

    @Test
    fun `delete moves file to trash and purgeExpiredTrash deletes old entries only`() = runTest {
        val vaultDir = tempFolder.newFolder("vault_trash")
        val dayMs = 86_400_000L
        var now = 1_700_000_000_000L
        val storage = LocalVaultStorage(
            vaultRootPathProvider = { vaultDir.absolutePath },
            clockMs = { now }
        )

        storage.writeFileAtomically("Notes/Old.md", now) { it.write("old".toByteArray()) }
        storage.deleteItem("Notes/Old.md", isDirectory = false, moveToTrash = true)

        val trashedOld = File(vaultDir, ".trash/Notes/Old.md")
        assertFalse(File(vaultDir, "Notes/Old.md").exists())
        assertTrue(trashedOld.exists())

        // Advance time by 35 days and delete a fresh file
        now += 35L * dayMs
        storage.writeFileAtomically("Notes/Recent.md", now) { it.write("recent".toByteArray()) }
        storage.deleteItem("Notes/Recent.md", isDirectory = false, moveToTrash = true)

        val trashedRecent = File(vaultDir, ".trash/Notes/Recent.md")
        assertTrue(trashedRecent.exists())

        val purged = storage.purgeExpiredTrash(retentionDays = 30)
        assertEquals(1, purged)
        assertFalse(trashedOld.exists())
        assertTrue(trashedRecent.exists())

        val stats = storage.getTrashStats()
        assertEquals(1, stats.fileCount)
        assertEquals(6L, stats.totalBytes)

        val emptied = storage.emptyTrashNow()
        assertEquals(1, emptied)
        assertEquals(0, storage.getTrashStats().fileCount)
    }
}
