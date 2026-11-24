package com.obsidiandrivesync.data.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.obsidiandrivesync.BuildConfig
import com.obsidiandrivesync.domain.sync.AuthExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
private data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresInSec: Long = 3600L,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String = "Bearer"
)

@Serializable
private data class TokenErrorDto(
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = ""
)

@Serializable
private data class DriveAboutDto(
    val user: DriveUserDto? = null
)

@Serializable
private data class DriveUserDto(
    val emailAddress: String? = null,
    val displayName: String? = null
)

data class AuthUiState(
    val isSignedIn: Boolean = false,
    val accountEmail: String = "",
    val hasClientIdConfigured: Boolean = false
)

/**
 * Direct RFC 8252 OAuth 2.0 + PKCE client using Android Custom Tabs and OkHttp.
 */
class GoogleOAuthManager(
    private val tokenStore: AuthTokenStore,
    private val httpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val _lastAuthError = MutableStateFlow<String?>(null)
    val lastAuthError: StateFlow<String?> = _lastAuthError.asStateFlow()

    val authStateFlow: Flow<AuthUiState> = tokenStore.sessionFlow.map { session ->
        AuthUiState(
            isSignedIn = session != null && session.refreshToken.isNotBlank(),
            accountEmail = session?.accountEmail.orEmpty(),
            hasClientIdConfigured = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank()
        )
    }

    fun isClientIdConfigured(): Boolean = BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isNotBlank()

    suspend fun launchSignIn(activityContext: Context) {
        val clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
        if (clientId.isBlank()) {
            _lastAuthError.value = "local.properties に GOOGLE_OAUTH_CLIENT_ID が設定されていません。"
            return
        }

        _lastAuthError.value = null
        val codeVerifier = generateRandomUrlSafe(64)
        val state = generateRandomUrlSafe(24)
        tokenStore.savePendingPkce(PendingPkceState(codeVerifier = codeVerifier, state = state))

        val codeChallenge = sha256Base64Url(codeVerifier)
        val redirectUri = "${BuildConfig.OAUTH_REDIRECT_SCHEME}:/oauth2redirect"

        val authUri = AUTH_ENDPOINT.toUri().buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", DRIVE_SCOPE)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent select_account")
            .build()

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        customTabsIntent.launchUrl(activityContext, authUri)
    }

    suspend fun handleRedirectUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            _lastAuthError.value = "Google ログインがキャンセルまたは拒否されました ($error)"
            return@withContext false
        }

        val code = uri.getQueryParameter("code")
        val returnedState = uri.getQueryParameter("state")
        val pending = tokenStore.consumePendingPkce()

        if (code.isNullOrBlank() || pending == null || pending.state != returnedState) {
            _lastAuthError.value = "OAuth コールバックの検証に失敗しました。"
            return@withContext false
        }

        val clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
        val redirectUri = "${BuildConfig.OAUTH_REDIRECT_SCHEME}:/oauth2redirect"

        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", pending.codeVerifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(form)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = runCatching { json.decodeFromString<TokenErrorDto>(bodyStr) }.getOrNull()
                _lastAuthError.value = "トークン取得失敗: ${err?.errorDescription ?: response.code}"
                return@withContext false
            }

            val tokenDto = json.decodeFromString<TokenResponseDto>(bodyStr)
            val expiresAt = System.currentTimeMillis() + (tokenDto.expiresInSec * 1000L)
            val email = fetchUserEmail(tokenDto.accessToken).orEmpty()

            tokenStore.saveSession(
                StoredAuthSession(
                    accessToken = tokenDto.accessToken,
                    refreshToken = tokenDto.refreshToken.orEmpty(),
                    expiresAtEpochMs = expiresAt,
                    accountEmail = email
                )
            )
            _lastAuthError.value = null
            true
        }
    }

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String = refreshMutex.withLock {
        val session = tokenStore.getSession()
            ?: throw AuthExpiredException("Google アカウントにログインしていません。")

        val now = System.currentTimeMillis()
        if (!forceRefresh && session.accessToken.isNotBlank() && now < session.expiresAtEpochMs - 60_000L) {
            return@withLock session.accessToken
        }

        if (session.refreshToken.isBlank()) {
            tokenStore.clearSession()
            throw AuthExpiredException("リフレッシュトークンが無効です。再ログインしてください。")
        }

        refreshAccessToken(session)
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val session = tokenStore.getSession()
        val tokenToRevoke = session?.refreshToken?.ifBlank { session.accessToken }
        if (!tokenToRevoke.isNullOrBlank()) {
            runCatching {
                val form = FormBody.Builder().add("token", tokenToRevoke).build()
                val req = Request.Builder().url(REVOKE_ENDPOINT).post(form).build()
                httpClient.newCall(req).execute().close()
            }
        }
        tokenStore.clearSession()
        _lastAuthError.value = null
    }

    private suspend fun refreshAccessToken(session: StoredAuthSession): String = withContext(Dispatchers.IO) {
        val clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", session.refreshToken)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(form)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val err = runCatching { json.decodeFromString<TokenErrorDto>(bodyStr) }.getOrNull()
                if (err?.error == "invalid_grant" || response.code == 400 || response.code == 401) {
                    tokenStore.clearSession()
                    throw AuthExpiredException("Google 認証の有効期限が切れました（再ログインが必要です）。")
                }
                throw IllegalStateException("トークン更新エラー (${response.code}): ${err?.errorDescription ?: bodyStr}")
            }

            val tokenDto = json.decodeFromString<TokenResponseDto>(bodyStr)
            val newExpiresAt = System.currentTimeMillis() + (tokenDto.expiresInSec * 1000L)
            val updatedEmail = session.accountEmail.ifBlank {
                fetchUserEmail(tokenDto.accessToken).orEmpty()
            }
            val updatedSession = session.copy(
                accessToken = tokenDto.accessToken,
                refreshToken = tokenDto.refreshToken?.ifBlank { session.refreshToken } ?: session.refreshToken,
                expiresAtEpochMs = newExpiresAt,
                accountEmail = updatedEmail
            )
            tokenStore.saveSession(updatedSession)
            updatedSession.accessToken
        }
    }

    private fun fetchUserEmail(accessToken: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)")
                .header("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<DriveAboutDto>(body).user?.emailAddress
            }
        }.getOrNull()
    }

    private fun generateRandomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    }
}
