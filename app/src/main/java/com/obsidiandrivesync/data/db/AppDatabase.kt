package com.obsidiandrivesync.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.obsidiandrivesync.domain.model.BaselineEntry
import com.obsidiandrivesync.domain.sync.BaselineStoreGateway

@Database(
    entities = [
        SyncEntryEntity::class,
        SyncLogEntity::class,
        ConflictEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao
}

class RoomBaselineStore(private val dao: SyncDao) : BaselineStoreGateway {
    override suspend fun getAllEntries(): List<BaselineEntry> =
        dao.getAllEntries().map { it.toDomain() }

    override suspend fun upsert(entry: BaselineEntry) {
        dao.upsertEntry(SyncEntryEntity.fromDomain(entry))
    }

    override suspend fun deleteByPath(relativePath: String) {
        dao.deleteEntryByPath(relativePath)
    }
}
