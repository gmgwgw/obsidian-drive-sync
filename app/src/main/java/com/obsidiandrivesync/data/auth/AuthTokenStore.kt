package com.obsidiandrivesync.data.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auth_tokens",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Serializable
data class StoredAuthSession(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochMs: Long = 0L,
    val accountEmail: String = ""
)

@Serializable
data class PendingPkceState(
    val codeVerifier: String,
    val state: String
)

interface AuthTokenStorage {
    val sessionFlow: Flow<StoredAuthSession?>
    suspend fun getSession(): StoredAuthSession?
    suspend fun saveSession(session: StoredAuthSession)
    suspend fun clearSession()
    suspend fun savePendingPkce(pending: PendingPkceState)
    suspend fun consumePendingPkce(): PendingPkceState?
}

class AuthTokenStore(
    private val context: Context,
    private val cryptoManager: CryptoManager = CryptoManager()
) : AuthTokenStorage {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val ENCRYPTED_SESSION = stringPreferencesKey("encrypted_session")
        val ENCRYPTED_PENDING_PKCE = stringPreferencesKey("encrypted_pending_pkce")
    }

    override val sessionFlow: Flow<StoredAuthSession?> = context.authDataStore.data.map { prefs ->
        val encrypted = prefs[Keys.ENCRYPTED_SESSION] ?: return@map null
        decryptSessionOrNull(encrypted)
    }

    override suspend fun getSession(): StoredAuthSession? = sessionFlow.first()

    override suspend fun saveSession(session: StoredAuthSession) {
        val rawJson = json.encodeToString(session)
        val encrypted = cryptoManager.encrypt(rawJson)
        context.authDataStore.edit { it[Keys.ENCRYPTED_SESSION] = encrypted }
    }

    override suspend fun clearSession() {
        context.authDataStore.edit {
            it.remove(Keys.ENCRYPTED_SESSION)
            it.remove(Keys.ENCRYPTED_PENDING_PKCE)
        }
    }

    override suspend fun savePendingPkce(pending: PendingPkceState) {
        val rawJson = json.encodeToString(pending)
        val encrypted = cryptoManager.encrypt(rawJson)
        context.authDataStore.edit { it[Keys.ENCRYPTED_PENDING_PKCE] = encrypted }
    }

    override suspend fun consumePendingPkce(): PendingPkceState? {
        val encrypted = context.authDataStore.data.first()[Keys.ENCRYPTED_PENDING_PKCE] ?: return null
        context.authDataStore.edit { it.remove(Keys.ENCRYPTED_PENDING_PKCE) }
        return runCatching {
            val decrypted = cryptoManager.decrypt(encrypted)
            json.decodeFromString<PendingPkceState>(decrypted)
        }.getOrNull()
    }

    private fun decryptSessionOrNull(encrypted: String): StoredAuthSession? {
        return runCatching {
            val decrypted = cryptoManager.decrypt(encrypted)
            json.decodeFromString<StoredAuthSession>(decrypted)
        }.getOrNull()
    }
}
