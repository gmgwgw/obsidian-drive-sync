package com.obsidiandrivesync.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conflicts")
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val originalPath: String,
    val conflictCopyPath: String,
    val detectedAtMs: Long,
    val acknowledged: Boolean = false
)
