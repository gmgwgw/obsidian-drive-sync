package com.obsidiandrivesync.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.obsidiandrivesync.domain.model.BaselineEntry

@Entity(tableName = "sync_entries")
data class SyncEntryEntity(
    @PrimaryKey
    val relativePath: String,
    val driveFileId: String,
    val isDirectory: Boolean,
    val md5Checksum: String?,
    val localLastModifiedMs: Long,
    val localSizeBytes: Long,
    val remoteModifiedTimeMs: Long,
    val lastSyncedAtMs: Long
) {
    fun toDomain(): BaselineEntry = BaselineEntry(
        relativePath = relativePath,
        driveFileId = driveFileId,
        isDirectory = isDirectory,
        md5Checksum = md5Checksum,
        localLastModifiedMs = localLastModifiedMs,
        localSizeBytes = localSizeBytes,
        remoteModifiedTimeMs = remoteModifiedTimeMs,
        lastSyncedAtMs = lastSyncedAtMs
    )

    companion object {
        fun fromDomain(entry: BaselineEntry): SyncEntryEntity = SyncEntryEntity(
            relativePath = entry.relativePath,
            driveFileId = entry.driveFileId,
            isDirectory = entry.isDirectory,
            md5Checksum = entry.md5Checksum,
            localLastModifiedMs = entry.localLastModifiedMs,
            localSizeBytes = entry.localSizeBytes,
            remoteModifiedTimeMs = entry.remoteModifiedTimeMs,
            lastSyncedAtMs = entry.lastSyncedAtMs
        )
    }
}
