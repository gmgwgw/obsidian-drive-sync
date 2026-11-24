package com.obsidiandrivesync.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {

    @Query("SELECT * FROM sync_entries")
    suspend fun getAllEntries(): List<SyncEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: SyncEntryEntity)

    @Query("DELETE FROM sync_entries WHERE relativePath = :relativePath")
    suspend fun deleteEntryByPath(relativePath: String)

    @Query("DELETE FROM sync_entries")
    suspend fun clearAllEntries()

    @Insert
    suspend fun insertLog(log: SyncLogEntity): Long

    @Query("SELECT * FROM sync_logs ORDER BY startedAtMs DESC LIMIT 100")
    fun observeRecentLogs(): Flow<List<SyncLogEntity>>

    @Query("SELECT * FROM sync_logs ORDER BY startedAtMs DESC LIMIT 1")
    fun observeLatestLog(): Flow<SyncLogEntity?>

    @Query("SELECT * FROM sync_logs ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getLatestLog(): SyncLogEntity?

    @Query("DELETE FROM sync_logs")
    suspend fun clearLogs()

    @Insert
    suspend fun insertConflicts(conflicts: List<ConflictEntity>)

    @Query("SELECT * FROM conflicts WHERE acknowledged = 0 ORDER BY detectedAtMs DESC")
    fun observeUnacknowledgedConflicts(): Flow<List<ConflictEntity>>

    @Query("UPDATE conflicts SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledgeConflict(id: Long)

    @Query("UPDATE conflicts SET acknowledged = 1")
    suspend fun acknowledgeAllConflicts()
}
