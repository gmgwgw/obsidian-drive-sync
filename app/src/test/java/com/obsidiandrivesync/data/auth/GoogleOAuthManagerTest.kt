package com.obsidiandrivesync.data.auth

import com.obsidiandrivesync.domain.sync.AuthExpiredException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleOAuthManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var store: InMemoryAuthTokenStorage
    private lateinit var manager: GoogleOAuthManager

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        store = InMemoryAuthTokenStorage()
        manager = GoogleOAuthManager(
            tokenStore = store,
            httpClient = OkHttpClient(),
            clientId = "test-client-id",
            redirectScheme = "com.obsidiandrivesync.oauth",
            tokenEndpoint = server.url("/token").toString(),
            revokeEndpoint = server.url("/revoke").toString(),
            aboutEndpoint = server.url("/about").toString()
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `rejects mismatched state on redirect`() = runTest {
        store.savePendingPkce(PendingPkceState(codeVerifier = "verifier", state = "expected_state"))

        val ok = manager.handleRedirectParams(code = "auth_code", returnedState = "wrong_state", error = null)

        assertFalse(ok)
        assertNotNull(manager.lastAuthError.value)
        assertNull(store.getSession())
    }

    @Test
    fun `handles network failure during token exchange without crashing`() = runTest {
        store.savePendingPkce(PendingPkceState(codeVerifier = "verifier", state = "s1"))
        server.enqueue(
            MockResponse.Builder()
                .onRequestStart(SocketEffect.CloseSocket())
                .build()
        )

        val ok = manager.handleRedirectParams(code = "auth_code", returnedState = "s1", error = null)

        assertFalse(ok)
        assertNotNull(manager.lastAuthError.value)
        assertNull(store.getSession())
    }

    @Test
    fun `rejects login cleanly when refresh token is missing and no prior session exists`() = runTest {
        store.savePendingPkce(PendingPkceState(codeVerifier = "verifier", state = "s1"))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"access_token":"acc_1","expires_in":3600}""")
                .build()
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"user":{"emailAddress":"user@example.com"}}""")
                .build()
        )

        val ok = manager.handleRedirectParams(code = "auth_code", returnedState = "s1", error = null)

        assertFalse(ok)
        assertNotNull(manager.lastAuthError.value)
        assertNull(store.getSession())
    }

    @Test
    fun `refreshAccessToken clears session on invalid_grant and 401, preserves on transient 400 or 500`() = runTest {
        val expiredSession = StoredAuthSession(
            accessToken = "old_access",
            refreshToken = "refresh_1",
            expiresAtEpochMs = 0L,
            accountEmail = "user@example.com"
        )
        store.saveSession(expiredSession)

        // Transient 400 with invalid_request should NOT wipe the refresh token.
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .body("""{"error":"invalid_request","error_description":"temporary bad request"}""")
                .build()
        )
        val transientError = runCatching { manager.getValidAccessToken(forceRefresh = true) }.exceptionOrNull()
        assertTrue(transientError is IllegalStateException)
        assertNotNull(store.getSession())

        // invalid_grant SHOULD clear the session and throw AuthExpiredException.
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .body("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")
                .build()
        )
        val expiredError = runCatching { manager.getValidAccessToken(forceRefresh = true) }.exceptionOrNull()
        assertTrue(expiredError is AuthExpiredException)
        assertNull(store.getSession())

        // 401 SHOULD also clear the session and throw AuthExpiredException.
        store.saveSession(expiredSession)
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        val unauthorizedError = runCatching { manager.getValidAccessToken(forceRefresh = true) }.exceptionOrNull()
        assertTrue(unauthorizedError is AuthExpiredException)
        assertNull(store.getSession())
    }

    @Test
    fun `refreshAccessToken updates access token on success`() = runTest {
        store.saveSession(
            StoredAuthSession(
                accessToken = "old_access",
                refreshToken = "refresh_1",
                expiresAtEpochMs = 0L,
                accountEmail = "user@example.com"
            )
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"access_token":"new_access","expires_in":3600}""")
                .build()
        )

        val token = manager.getValidAccessToken(forceRefresh = true)
        assertEquals("new_access", token)
        assertEquals("refresh_1", store.getSession()?.refreshToken)
    }

    private class InMemoryAuthTokenStorage : AuthTokenStorage {
        private val state = MutableStateFlow<StoredAuthSession?>(null)
        private var pendingPkce: PendingPkceState? = null

        override val sessionFlow: Flow<StoredAuthSession?> = state
        override suspend fun getSession(): StoredAuthSession? = state.value
        override suspend fun saveSession(session: StoredAuthSession) {
            state.value = session
        }
        override suspend fun clearSession() {
            state.value = null
            pendingPkce = null
        }
        override suspend fun savePendingPkce(pending: PendingPkceState) {
            pendingPkce = pending
        }
        override suspend fun consumePendingPkce(): PendingPkceState? {
            val current = pendingPkce
            pendingPkce = null
            return current
        }
    }
}
