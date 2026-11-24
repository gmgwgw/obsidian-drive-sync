package com.obsidiandrivesync.ui.common

import com.obsidiandrivesync.domain.model.SyncPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `formatBytes formats B, KB, MB, and GB cleanly`() {
        assertEquals("0 B", formatBytes(-10L))
        assertEquals("0 B", formatBytes(0L))
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1.5 KB", formatBytes(1536L))
        assertEquals("2.0 MB", formatBytes(2L * 1024L * 1024L))
        assertEquals("1.00 GB", formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `formatStatusLabel maps all SyncPhase names`() {
        assertEquals("成功", formatStatusLabel(SyncPhase.COMPLETED.name))
        assertEquals("キャンセル", formatStatusLabel(SyncPhase.CANCELLED.name))
        assertEquals("削除ブレーキ停止", formatStatusLabel(SyncPhase.DELETION_GUARD_BLOCKED.name))
        assertEquals("認証期限切れ", formatStatusLabel(SyncPhase.AUTH_EXPIRED.name))
        assertEquals("失敗", formatStatusLabel(SyncPhase.FAILED.name))
        assertEquals("UNKNOWN", formatStatusLabel("UNKNOWN"))
    }

    @Test
    fun `formatTriggerLabel maps trigger sources`() {
        assertEquals("手動", formatTriggerLabel("manual"))
        assertEquals("定期", formatTriggerLabel("periodic"))
        assertEquals("クイックタイル", formatTriggerLabel("tile"))
        assertEquals("ウィジェット", formatTriggerLabel("widget"))
        assertEquals("other", formatTriggerLabel("other"))
    }
}
