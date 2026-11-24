package com.obsidiandrivesync.data.drive

import com.obsidiandrivesync.domain.sync.IgnoreRuleMatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleDriveClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GoogleDriveClient

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/drive/v3").toString()
        val uploadUrl = server.url("/upload/drive/v3").toString()
        client = GoogleDriveClient(
            httpClient = OkHttpClient(),
            tokenProvider = { _ -> "test_token" },
            baseApiUrl = baseUrl,
            baseUploadUrl = uploadUrl
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `listFolderTree handles pagination, skips google native docs, and deduplicates names`() = runTest {
        // Page 1 of root
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "nextPageToken": "p2",
                  "files": [
                    {"id":"d1","name":"Daily","mimeType":"application/vnd.google-apps.folder","modifiedTime":"2026-11-24T00:00:00Z"},
                    {"id":"g1","name":"MyDoc","mimeType":"application/vnd.google-apps.document","modifiedTime":"2026-11-24T00:00:00Z"}
                  ]
                }
                """.trimIndent()
            )
        )
        // Page 2 of root (contains duplicate name Note.md)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "files": [
                    {"id":"f_old","name":"Note.md","mimeType":"text/markdown","md5Checksum":"111","modifiedTime":"2026-11-20T00:00:00Z","size":"10"},
                    {"id":"f_new","name":"Note.md","mimeType":"text/markdown","md5Checksum":"222","modifiedTime":"2026-11-24T05:00:00Z","size":"20"}
                  ]
                }
                """.trimIndent()
            )
        )
        // Children of Daily (d1)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "files": [
                    {"id":"f_daily","name":"today.md","mimeType":"text/markdown","md5Checksum":"333","modifiedTime":"2026-11-24T06:00:00Z","size":"30"}
                  ]
                }
                """.trimIndent()
            )
        )

        val result = client.listFolderTree("root_id", IgnoreRuleMatcher(IgnoreRuleMatcher.DEFAULT_PATTERNS))
        val paths = result.items.associateBy { it.relativePath }

        assertEquals(3, paths.size)
        assertTrue(paths.containsKey("Daily"))
        assertEquals("f_new", paths["Note.md"]?.driveFileId)
        assertEquals("f_daily", paths["Daily/today.md"]?.driveFileId)
        assertEquals(2, result.skippedWarnings.size)
    }

    @Test
    fun `trashFile retries on 429 and sends trashed true patch`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"f1","trashed":true}"""))

        client.trashFile("f1")

        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("PATCH", first.method)
        assertEquals("PATCH", second.method)
        assertTrue(second.body.readUtf8().contains("\"trashed\":true"))
    }
}
