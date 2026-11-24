package com.obsidiandrivesync.domain.sync

import com.obsidiandrivesync.domain.model.BaselineEntry
import com.obsidiandrivesync.domain.model.LocalFileItem
import com.obsidiandrivesync.domain.model.RemoteFileItem
import com.obsidiandrivesync.domain.model.SyncAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    private val planner = SyncPlanner()

    @Test
    fun `plans upload for new local file and download for new remote file`() {
        val plan = planner.plan(
            baseline = emptyList(),
            localItems = listOf(LocalFileItem("local.md", false, 100L, 1_000L)),
            remoteItems = listOf(RemoteFileItem("r1", "root", "remote.md", "remote.md", false, 200L, 2_000L, "md5r")),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { "md5l" }
        )

        assertFalse(plan.deletionGuardTriggered)
        assertEquals(2, plan.actions.size)
        assertTrue(plan.actions.any { it is SyncAction.UploadNew && it.relativePath == "local.md" })
        assertTrue(plan.actions.any { it is SyncAction.DownloadNew && it.relativePath == "remote.md" })
    }

    @Test
    fun `skips md5 check when size and timestamp are within tolerance`() {
        var md5Called = false
        val plan = planner.plan(
            baseline = listOf(
                BaselineEntry("note.md", "r1", false, "abc", 10_000L, 50L, 10_000L, 10_000L)
            ),
            localItems = listOf(LocalFileItem("note.md", false, 50L, 11_500L)),
            remoteItems = listOf(RemoteFileItem("r1", "root", "note.md", "note.md", false, 50L, 10_000L, "abc")),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = {
                md5Called = true
                "abc"
            }
        )

        assertFalse(md5Called)
        assertTrue(plan.actions.isEmpty())
    }

    @Test
    fun `plans upload update when local changed and remote unchanged`() {
        val plan = planner.plan(
            baseline = listOf(BaselineEntry("note.md", "r1", false, "oldmd5", 1_000L, 10L, 1_000L, 1_000L)),
            localItems = listOf(LocalFileItem("note.md", false, 20L, 5_000L)),
            remoteItems = listOf(RemoteFileItem("r1", "root", "note.md", "note.md", false, 10L, 1_000L, "oldmd5")),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { "newmd5" }
        )

        assertEquals(1, plan.actions.size)
        val action = plan.actions.single() as SyncAction.UploadUpdate
        assertEquals("r1", action.driveFileId)
    }

    @Test
    fun `plans download update when remote changed and local unchanged`() {
        val plan = planner.plan(
            baseline = listOf(BaselineEntry("note.md", "r1", false, "oldmd5", 1_000L, 10L, 1_000L, 1_000L)),
            localItems = listOf(LocalFileItem("note.md", false, 10L, 1_000L)),
            remoteItems = listOf(RemoteFileItem("r1", "root", "note.md", "note.md", false, 30L, 5_000L, "newmd5")),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { "oldmd5" }
        )

        assertEquals(1, plan.actions.size)
        assertTrue(plan.actions.single() is SyncAction.DownloadUpdate)
    }

    @Test
    fun `resolves both changed with different md5 as conflict, same md5 as baseline only`() {
        val conflictPlan = planner.plan(
            baseline = listOf(BaselineEntry("note.md", "r1", false, "base", 1_000L, 10L, 1_000L, 1_000L)),
            localItems = listOf(LocalFileItem("note.md", false, 15L, 5_000L)),
            remoteItems = listOf(RemoteFileItem("r1", "root", "note.md", "note.md", false, 18L, 6_000L, "remoteMd5")),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { "localMd5" }
        )
        assertTrue(conflictPlan.actions.single() is SyncAction.ResolveConflict)

        val sameContentPlan = planner.plan(
            baseline = listOf(BaselineEntry("note.md", "r1", false, "base", 1_000L, 10L, 1_000L, 1_000L)),
            localItems = listOf(LocalFileItem("note.md", false, 15L, 5_000L)),
            remoteItems = listOf(RemoteFileItem("r1", "root", "note.md", "note.md", false, 15L, 6_000L, "sameMd5")),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { "sameMd5" }
        )
        assertTrue(sameContentPlan.actions.single() is SyncAction.RecordBaselineOnly)
    }

    @Test
    fun `preserves edit when one side deleted and other side modified`() {
        val plan = planner.plan(
            baseline = listOf(
                BaselineEntry("deletedLocalModifiedRemote.md", "r1", false, "b1", 1_000L, 10L, 1_000L, 1_000L),
                BaselineEntry("deletedRemoteModifiedLocal.md", "r2", false, "b2", 1_000L, 10L, 1_000L, 1_000L),
                BaselineEntry("deletedLocalUnchangedRemote.md", "r3", false, "b3", 1_000L, 10L, 1_000L, 1_000L),
                BaselineEntry("deletedRemoteUnchangedLocal.md", "r4", false, "b4", 1_000L, 10L, 1_000L, 1_000L),
                BaselineEntry("deletedBoth.md", "r5", false, "b5", 1_000L, 10L, 1_000L, 1_000L)
            ),
            localItems = listOf(
                LocalFileItem("deletedRemoteModifiedLocal.md", false, 99L, 9_000L),
                LocalFileItem("deletedRemoteUnchangedLocal.md", false, 10L, 1_000L)
            ),
            remoteItems = listOf(
                RemoteFileItem("r1", "root", "deletedLocalModifiedRemote.md", "deletedLocalModifiedRemote.md", false, 50L, 9_000L, "newR1"),
                RemoteFileItem("r3", "root", "deletedLocalUnchangedRemote.md", "deletedLocalUnchangedRemote.md", false, 10L, 1_000L, "b3")
            ),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { "modifiedLocalMd5" }
        )

        assertTrue(plan.actions.any { it is SyncAction.DownloadNew && it.relativePath == "deletedLocalModifiedRemote.md" })
        assertTrue(plan.actions.any { it is SyncAction.UploadNew && it.relativePath == "deletedRemoteModifiedLocal.md" })
        assertTrue(plan.actions.any { it is SyncAction.DeleteRemote && it.relativePath == "deletedLocalUnchangedRemote.md" })
        assertTrue(plan.actions.any { it is SyncAction.DeleteLocal && it.relativePath == "deletedRemoteUnchangedLocal.md" })
        assertTrue(plan.actions.any { it is SyncAction.CleanupBaseline && it.relativePath == "deletedBoth.md" })
    }

    @Test
    fun `deletion guard blocks when deletions meet threshold unless overridden`() {
        val baseline = (1..12).map { i ->
            BaselineEntry("f$i.md", "r$i", false, "md5_$i", 1_000L, 10L, 1_000L, 1_000L)
        }
        val remoteRemaining = (11..12).map { i ->
            RemoteFileItem("r$i", "root", "f$i.md", "f$i.md", false, 10L, 1_000L, "md5_$i")
        }
        val localRemaining = (11..12).map { i ->
            LocalFileItem("f$i.md", false, 10L, 1_000L)
        }

        val blocked = planner.plan(
            baseline = baseline,
            localItems = localRemaining,
            remoteItems = remoteRemaining.plus(
                (1..10).map { i -> RemoteFileItem("r$i", "root", "f$i.md", "f$i.md", false, 10L, 1_000L, "md5_$i") }
            ),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { "same" }
        )

        assertTrue(blocked.deletionGuardTriggered)
        assertEquals(10, blocked.pendingDeletionCount)
        assertTrue(blocked.actions.isEmpty())

        val allowed = planner.plan(
            baseline = baseline,
            localItems = localRemaining,
            remoteItems = remoteRemaining.plus(
                (1..10).map { i -> RemoteFileItem("r$i", "root", "f$i.md", "f$i.md", false, 10L, 1_000L, "md5_$i") }
            ),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = true,
            localMd5Provider = { "same" }
        )

        assertFalse(allowed.deletionGuardTriggered)
        assertEquals(10, allowed.actions.count { it is SyncAction.DeleteRemote })
    }

    @Test
    fun `buildConflictPath inserts conflict suffix before extension`() {
        assertEquals(
            "Notes/Idea (conflict 2026-11-24 10-15-30).md",
            SyncPlanner.buildConflictPath("Notes/Idea.md", "2026-11-24 10-15-30")
        )
        assertEquals(
            "README (conflict 2026-11-24 10-15-30)",
            SyncPlanner.buildConflictPath("README", "2026-11-24 10-15-30")
        )
    }

    @Test
    fun `does not delete folder when child file is added or modified on other side`() {
        val plan = planner.plan(
            baseline = listOf(
                BaselineEntry("Projects", "d_proj", true, null, 1_000L, 0L, 1_000L, 1_000L),
                BaselineEntry("Projects/old.md", "f_old", false, "b_old", 1_000L, 10L, 1_000L, 1_000L)
            ),
            localItems = emptyList(),
            remoteItems = listOf(
                RemoteFileItem("d_proj", "root", "Projects", "Projects", true, 0L, 1_000L, null),
                RemoteFileItem("f_old", "d_proj", "old.md", "Projects/old.md", false, 10L, 1_000L, "b_old"),
                RemoteFileItem("f_new", "d_proj", "new.md", "Projects/new.md", false, 20L, 5_000L, "new_md5")
            ),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { null }
        )

        assertFalse(plan.actions.any { it is SyncAction.DeleteRemote && it.relativePath == "Projects" })
        assertTrue(plan.actions.any { it is SyncAction.CreateLocalDir && it.relativePath == "Projects" })
        assertTrue(plan.actions.any { it is SyncAction.DownloadNew && it.relativePath == "Projects/new.md" })
        assertTrue(plan.actions.any { it is SyncAction.DeleteRemote && it.relativePath == "Projects/old.md" })
    }

    @Test
    fun `skips colliding file vs directory paths and emits warning`() {
        val plan = planner.plan(
            baseline = emptyList(),
            localItems = listOf(
                LocalFileItem("Notes", false, 10L, 1_000L),
                LocalFileItem("Valid.md", false, 10L, 1_000L)
            ),
            remoteItems = listOf(
                RemoteFileItem("d1", "root", "Notes", "Notes", true, 0L, 1_000L, null),
                RemoteFileItem("f1", "d1", "child.md", "Notes/child.md", false, 10L, 1_000L, "abc")
            ),
            deletionGuardEnabled = true,
            allowLargeDeletionOnce = false,
            localMd5Provider = { "local" }
        )

        assertEquals(1, plan.warnings.size)
        assertTrue(plan.warnings.single().contains("Notes"))
        assertFalse(plan.actions.any { it.relativePath == "Notes" || it.relativePath.startsWith("Notes/") })
        assertTrue(plan.actions.any { it is SyncAction.UploadNew && it.relativePath == "Valid.md" })
    }
}
