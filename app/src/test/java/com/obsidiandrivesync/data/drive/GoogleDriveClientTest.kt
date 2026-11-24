package com.obsidiandrivesync.data.drive

import com.obsidiandrivesync.data.local.LocalVaultStorage
import com.obsidiandrivesync.domain.sync.IgnoreRuleMatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoogleDriveClientTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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
        server.close()
    }

    @Test
    fun `listFolderTree handles pagination, skips google native docs, and deduplicates names`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
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
                .build()
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "files": [
                        {"id":"f_old","name":"Note.md","mimeType":"text/markdown","md5Checksum":"111","modifiedTime":"2026-11-20T00:00:00Z","size":"10"},
                        {"id":"f_new","name":"Note.md","mimeType":"text/markdown","md5Checksum":"222","modifiedTime":"2026-11-24T05:00:00Z","size":"20"}
                      ]
                    }
                    """.trimIndent()
                )
                .build()
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "files": [
                        {"id":"f_daily","name":"today.md","mimeType":"text/markdown","md5Checksum":"333","modifiedTime":"2026-11-24T06:00:00Z","size":"30"}
                      ]
                    }
                    """.trimIndent()
                )
                .build()
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
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "0").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"f1","trashed":true}""").build())

        client.trashFile("f1")

        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("PATCH", first.method)
        assertEquals("PATCH", second.method)
        assertTrue(second.body?.utf8().orEmpty().contains("\"trashed\":true"))
    }

    @Test
    fun `uploadNewFile sends multipart related and updateFileContent sends media patch`() = runTest {
        val vaultDir = tempFolder.newFolder("vault_upload")
        val storage = LocalVaultStorage(vaultRootPathProvider = { vaultDir.absolutePath })
        storage.writeFileAtomically("hello.md", 1_700_000_000_000L) {
            it.write("# Hello".toByteArray())
        }

        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"id":"up_1","name":"hello.md","parents":["root_id"],"mimeType":"text/markdown","md5Checksum":"abc","modifiedTime":"2026-11-24T08:00:00Z","size":"7"}""")
                .build()
        )
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"id":"up_1","name":"hello.md","parents":["root_id"],"mimeType":"text/markdown","md5Checksum":"def","modifiedTime":"2026-11-24T09:00:00Z","size":"7"}""")
                .build()
        )

        val uploaded = client.uploadNewFile("root_id", "hello.md", "hello.md", storage, 7L, "text/markdown")
        assertEquals("up_1", uploaded.driveFileId)
        val uploadReq = server.takeRequest()
        assertEquals("POST", uploadReq.method)
        assertTrue(uploadReq.headers["Content-Type"].orEmpty().startsWith("multipart/related"))
        assertTrue(uploadReq.body?.utf8().orEmpty().contains("# Hello"))

        val updated = client.updateFileContent("up_1", "hello.md", storage, 7L, "text/markdown")
        assertEquals("def", updated.md5Checksum)
        val patchReq = server.takeRequest()
        assertEquals("PATCH", patchReq.method)
        assertTrue(patchReq.target.contains("uploadType=media"))
        assertEquals("# Hello", patchReq.body?.utf8())
    }
}
