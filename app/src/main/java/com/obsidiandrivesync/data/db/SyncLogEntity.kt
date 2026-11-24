package com.obsidiandrivesync.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val triggerSource: String,
    val status: String,
    val uploadedCount: Int,
    val downloadedCount: Int,
    val deletedLocalCount: Int,
    val deletedRemoteCount: Int,
    val conflictCount: Int,
    val trashPurgedCount: Int,
    val warningsText: String,
    val errorMessage: String?
)
