package com.obsidiandrivesync.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.obsidiandrivesync.domain.sync.IgnoreRuleMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sync_settings")

data class SyncSettings(
    val localVaultPath: String = "",
    val driveFolderId: String = "",
    val driveFolderName: String = "",
    val periodicIntervalMinutes: Int = 60,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val ignorePatterns: List<String> = IgnoreRuleMatcher.DEFAULT_PATTERNS,
    val moveRemoteDeletedToTrash: Boolean = true,
    val trashRetentionDays: Int = 30,
    val deletionGuardEnabled: Boolean = true
) {
    val isConfigured: Boolean
        get() = localVaultPath.isNotBlank() && driveFolderId.isNotBlank()
}

class SyncPreferencesRepository(private val context: Context) {

    private object Keys {
        val LOCAL_VAULT_PATH = stringPreferencesKey("local_vault_path")
        val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
        val DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")
        val PERIODIC_INTERVAL_MINUTES = intPreferencesKey("periodic_interval_minutes")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val IGNORE_PATTERNS = stringPreferencesKey("ignore_patterns_multiline")
        val MOVE_TO_TRASH = booleanPreferencesKey("move_remote_deleted_to_trash")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val DELETION_GUARD_ENABLED = booleanPreferencesKey("deletion_guard_enabled")
    }

    val settingsFlow: Flow<SyncSettings> = context.settingsDataStore.data.map { prefs ->
        val rawPatterns = prefs[Keys.IGNORE_PATTERNS]
        val parsedPatterns = if (rawPatterns == null) {
            IgnoreRuleMatcher.DEFAULT_PATTERNS
        } else {
            rawPatterns.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        SyncSettings(
            localVaultPath = prefs[Keys.LOCAL_VAULT_PATH].orEmpty(),
            driveFolderId = prefs[Keys.DRIVE_FOLDER_ID].orEmpty(),
            driveFolderName = prefs[Keys.DRIVE_FOLDER_NAME].orEmpty(),
            periodicIntervalMinutes = prefs[Keys.PERIODIC_INTERVAL_MINUTES] ?: 60,
            wifiOnly = prefs[Keys.WIFI_ONLY] ?: true,
            chargingOnly = prefs[Keys.CHARGING_ONLY] ?: false,
            ignorePatterns = parsedPatterns,
            moveRemoteDeletedToTrash = prefs[Keys.MOVE_TO_TRASH] ?: true,
            trashRetentionDays = prefs[Keys.TRASH_RETENTION_DAYS] ?: 30,
            deletionGuardEnabled = prefs[Keys.DELETION_GUARD_ENABLED] ?: true
        )
    }

    suspend fun getSettings(): SyncSettings = settingsFlow.first()

    suspend fun setLocalVaultPath(path: String) {
        context.settingsDataStore.edit { it[Keys.LOCAL_VAULT_PATH] = path }
    }

    suspend fun setDriveFolder(folderId: String, folderName: String) {
        context.settingsDataStore.edit {
            it[Keys.DRIVE_FOLDER_ID] = folderId
            it[Keys.DRIVE_FOLDER_NAME] = folderName
        }
    }

    suspend fun setPeriodicIntervalMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.PERIODIC_INTERVAL_MINUTES] = minutes }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }

    suspend fun setChargingOnly(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.CHARGING_ONLY] = enabled }
    }

    suspend fun setIgnorePatterns(patterns: List<String>) {
        context.settingsDataStore.edit {
            it[Keys.IGNORE_PATTERNS] = patterns.joinToString("\n")
        }
    }

    suspend fun resetIgnorePatternsToDefault() {
        context.settingsDataStore.edit {
            it.remove(Keys.IGNORE_PATTERNS)
        }
    }

    suspend fun setMoveRemoteDeletedToTrash(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.MOVE_TO_TRASH] = enabled }
    }

    suspend fun setTrashRetentionDays(days: Int) {
        context.settingsDataStore.edit { it[Keys.TRASH_RETENTION_DAYS] = days }
    }

    suspend fun setDeletionGuardEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DELETION_GUARD_ENABLED] = enabled }
    }
}
