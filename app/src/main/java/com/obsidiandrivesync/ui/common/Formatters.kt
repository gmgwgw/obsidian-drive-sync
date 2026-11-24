package com.obsidiandrivesync.ui.common

import com.obsidiandrivesync.domain.model.SyncPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_TIME_FULL_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT)
        .withZone(ZoneId.systemDefault())

private val DATE_TIME_LOG_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM/dd HH:mm:ss", Locale.ROOT)
        .withZone(ZoneId.systemDefault())

private val DATE_TIME_SHORT_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault())

fun formatDateTime(epochMs: Long): String =
    DATE_TIME_FULL_FORMATTER.format(Instant.ofEpochMilli(epochMs))

fun formatLogDateTime(epochMs: Long): String =
    DATE_TIME_LOG_FORMATTER.format(Instant.ofEpochMilli(epochMs))

fun formatShortDateTime(epochMs: Long): String =
    DATE_TIME_SHORT_FORMATTER.format(Instant.ofEpochMilli(epochMs))

fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatStatusLabel(statusName: String): String = when (statusName) {
    SyncPhase.COMPLETED.name -> "成功"
    SyncPhase.CANCELLED.name -> "キャンセル"
    SyncPhase.ALREADY_RUNNING.name -> "実行中スキップ"
    SyncPhase.DELETION_GUARD_BLOCKED.name -> "削除ブレーキ停止"
    SyncPhase.AUTH_EXPIRED.name -> "認証期限切れ"
    SyncPhase.FAILED.name -> "失敗"
    else -> statusName
}

fun formatTriggerLabel(source: String): String = when (source) {
    "manual" -> "手動"
    "periodic" -> "定期"
    "tile" -> "クイックタイル"
    "widget" -> "ウィジェット"
    else -> source
}
