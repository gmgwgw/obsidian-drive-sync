package com.obsidiandrivesync.data.drive

import com.obsidiandrivesync.domain.model.RemoteFileItem
import com.obsidiandrivesync.domain.sync.AuthExpiredException
import com.obsidiandrivesync.domain.sync.IgnoreRuleMatcher
import com.obsidiandrivesync.domain.sync.LocalVaultGateway
import com.obsidiandrivesync.domain.sync.RemoteDriveGateway
import com.obsidiandrivesync.domain.sync.RemoteTreeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.OutputStream
import java.time.Instant

@Serializable
data class DriveFileDto(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val parents: List<String> = emptyList(),
    val md5Checksum: String? = null,
    val modifiedTime: String? = null,
    val size: String? = null,
    val trashed: Boolean = false
)

@Serializable
data class DriveFileListDto(
    val nextPageToken: String? = null,
    val files: List<DriveFileDto> = emptyList()
)

@Serializable
private data class DriveCreateMetadataDto(
    val name: String,
    val mimeType: String? = null,
    val parents: List<String>? = null
)

@Serializable
private data class DriveTrashDto(
    val trashed: Boolean
)

data class DriveFolderSummary(
    val id: String,
    val name: String
)

fun interface AccessTokenProvider {
    suspend fun getAccessToken(forceRefresh: Boolean): String
}

class GoogleDriveClient(
    private val httpClient: OkHttpClient,
    private val tokenProvider: AccessTokenProvider,
    private val baseApiUrl: String = DEFAULT_BASE_API_URL,
    private val baseUploadUrl: String = DEFAULT_BASE_UPLOAD_URL
) : RemoteDriveGateway {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listChildFolders(parentId: String): List<DriveFolderSummary> = withContext(Dispatchers.IO) {
        val children = listDirectChildren(parentId)
        children
            .filter { !it.trashed && it.mimeType == FOLDER_MIME_TYPE }
            .sortedBy { it.name.lowercase() }
            .map { DriveFolderSummary(id = it.id, name = it.name) }
    }

    override suspend fun listFolderTree(rootFolderId: String, ignoreMatcher: IgnoreRuleMatcher): RemoteTreeResult =
        withContext(Dispatchers.IO) {
            val resultItems = mutableListOf<RemoteFileItem>()
            val skippedWarnings = mutableListOf<String>()

            data class FolderQueueNode(val folderId: String, val relativePrefix: String)

            val queue = ArrayDeque<FolderQueueNode>()
            queue.add(FolderQueueNode(rootFolderId, ""))

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val rawChildren = listDirectChildren(current.folderId)
                    .filter { !it.trashed }

                // Deduplicate same-name entries in the same Drive folder by keeping the newest modifiedTime.
                val groupedByName = rawChildren.groupBy { it.name }
                for ((name, duplicates) in groupedByName) {
                    val chosen = if (duplicates.size > 1) {
                        val sorted = duplicates.sortedByDescending { parseIsoTimeMs(it.modifiedTime) }
                        skippedWarnings += "Drive 同名重複を検出 (${current.relativePrefix}/$name): 最新版のみ同期対象にしました。"
                        sorted.first()
                    } else {
                        duplicates.first()
                    }

                    val relPath = if (current.relativePrefix.isEmpty()) {
                        name
                    } else {
                        "${current.relativePrefix}/$name"
                    }

                    val isDir = chosen.mimeType == FOLDER_MIME_TYPE
                    if (ignoreMatcher.isIgnored(relPath, isDirectory = isDir)) {
                        continue
                    }

                    if (!isDir && chosen.mimeType.startsWith(GOOGLE_APPS_PREFIX)) {
                        skippedWarnings += "Google ドキュメント形式のためスキップ: $relPath"
                        continue
                    }

                    val item = chosen.toRemoteFileItem(parentId = current.folderId, relativePath = relPath)
                    resultItems += item
                    if (isDir) {
                        queue.add(FolderQueueNode(chosen.id, relPath))
                    }
                }
            }

            RemoteTreeResult(items = resultItems, skippedWarnings = skippedWarnings)
        }

    override suspend fun createFolder(parentId: String, name: String): RemoteFileItem = withContext(Dispatchers.IO) {
        val metadataJson = json.encodeToString(
            DriveCreateMetadataDto(
                name = name,
                mimeType = FOLDER_MIME_TYPE,
                parents = listOf(parentId)
            )
        )
        val url = "$baseApiUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("fields", FILE_FIELDS)
            .build()

        val dto = executeWithRetry(url.toString()) { token ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }.let { body -> json.decodeFromString<DriveFileDto>(body) }

        dto.toRemoteFileItem(parentId = parentId, relativePath = name)
    }

    override suspend fun downloadFile(driveFileId: String, outputStream: OutputStream): Unit = withContext(Dispatchers.IO) {
        val url = "$baseApiUrl/files/$driveFileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()

        executeStreamWithRetry(url.toString(), { token ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }) { inputStream ->
            inputStream.copyTo(outputStream, bufferSize = 32 * 1024)
            outputStream.flush()
        }
    }

    override suspend fun uploadNewFile(
        parentId: String,
        name: String,
        relativePath: String,
        localGateway: LocalVaultGateway,
        sizeBytes: Long,
        mimeType: String
    ): RemoteFileItem = withContext(Dispatchers.IO) {
        val metadataJson = json.encodeToString(
            DriveCreateMetadataDto(
                name = name,
                parents = listOf(parentId)
            )
        )
        val url = "$baseUploadUrl/files".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()

        val dto = executeWithRetry(url.toString()) { token ->
            val fileBody = streamingLocalRequestBody(localGateway, relativePath, sizeBytes, mimeType)
            val multipartBody = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
                .addPart(fileBody)
                .build()

            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(multipartBody)
                .build()
        }.let { body -> json.decodeFromString<DriveFileDto>(body) }

        dto.toRemoteFileItem(parentId = parentId, relativePath = relativePath)
    }

    override suspend fun updateFileContent(
        driveFileId: String,
        relativePath: String,
        localGateway: LocalVaultGateway,
        sizeBytes: Long,
        mimeType: String
    ): RemoteFileItem = withContext(Dispatchers.IO) {
        val url = "$baseUploadUrl/files/$driveFileId".toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", FILE_FIELDS)
            .build()

        val dto = executeWithRetry(url.toString()) { token ->
            val fileBody = streamingLocalRequestBody(localGateway, relativePath, sizeBytes, mimeType)
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .patch(fileBody)
                .build()
        }.let { body -> json.decodeFromString<DriveFileDto>(body) }

        dto.toRemoteFileItem(
            parentId = dto.parents.firstOrNull().orEmpty(),
            relativePath = relativePath
        )
    }

    override suspend fun trashFile(driveFileId: String): Unit = withContext(Dispatchers.IO) {
        val url = "$baseApiUrl/files/$driveFileId".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,trashed")
            .build()
        val payload = json.encodeToString(DriveTrashDto(trashed = true))

        executeWithRetry(url.toString()) { token ->
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
    }

    private suspend fun listDirectChildren(parentId: String): List<DriveFileDto> {
        val all = mutableListOf<DriveFileDto>()
        var pageToken: String? = null
        val escapedParentId = parentId.replace("'", "\\'")
        val query = "'$escapedParentId' in parents and trashed = false"

        do {
            val urlBuilder = "$baseApiUrl/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("spaces", "drive")
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
            if (pageToken != null) {
                urlBuilder.addQueryParameter("pageToken", pageToken)
            }
            val url = urlBuilder.build()

            val bodyStr = executeWithRetry(url.toString()) { token ->
                Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
            }
            val page = json.decodeFromString<DriveFileListDto>(bodyStr)
            all += page.files
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrBlank())

        return all
    }

    private fun streamingLocalRequestBody(
        localGateway: LocalVaultGateway,
        relativePath: String,
        sizeBytes: Long,
        mimeType: String
    ): RequestBody {
        val mediaType = mimeType.toMediaType()
        return object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = if (sizeBytes >= 0L) sizeBytes else -1L
            override fun writeTo(sink: BufferedSink) {
                val inputStream = kotlinx.coroutines.runBlocking {
                    localGateway.openInputStream(relativePath)
                }
                inputStream.use { stream ->
                    sink.writeAll(stream.source())
                }
            }
        }
    }

    private suspend fun executeWithRetry(
        label: String,
        requestBuilder: suspend (String) -> Request
    ): String {
        var resultBody = ""
        executeStreamWithRetry(label, requestBuilder) { stream ->
            resultBody = stream.bufferedReader(Charsets.UTF_8).readText()
        }
        return resultBody
    }

    private suspend fun executeStreamWithRetry(
        label: String,
        requestBuilder: suspend (String) -> Request,
        onSuccessStream: (java.io.InputStream) -> Unit
    ) {
        var attempt = 0
        var forceRefresh = false
        var lastError: Throwable? = null

        while (attempt <= MAX_RETRIES) {
            val token = tokenProvider.getAccessToken(forceRefresh)
            forceRefresh = false
            val request = requestBuilder(token)

            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val body = response.body ?: throw IOException("Empty response body for $label")
                            onSuccessStream(body.byteStream())
                            return
                        }
                        response.code == 401 && attempt == 0 -> {
                            forceRefresh = true
                            attempt++
                        }
                        response.code == 401 -> {
                            throw AuthExpiredException("Google Drive API の認証が拒否されました (401)。")
                        }
                        response.code == 429 || response.code in 500..599 -> {
                            val errBody = response.body?.string().orEmpty()
                            lastError = IOException("Drive API HTTP ${response.code}: $errBody")
                            if (attempt == MAX_RETRIES) throw lastError!!
                            val retryAfterSec = response.header("Retry-After")?.toLongOrNull()
                            val delayMs = if (retryAfterSec != null) retryAfterSec * 1000L else (500L shl attempt)
                            delay(delayMs)
                            attempt++
                        }
                        else -> {
                            val errBody = response.body?.string().orEmpty()
                            throw IOException("Drive API HTTP ${response.code} ($label): $errBody")
                        }
                    }
                }
            } catch (io: IOException) {
                lastError = io
                if (attempt == MAX_RETRIES) throw io
                delay(500L shl attempt)
                attempt++
            }
        }
        throw lastError ?: IOException("Drive API request failed for $label")
    }

    private fun DriveFileDto.toRemoteFileItem(parentId: String, relativePath: String): RemoteFileItem {
        return RemoteFileItem(
            driveFileId = id,
            parentId = parents.firstOrNull() ?: parentId,
            name = name,
            relativePath = relativePath,
            isDirectory = mimeType == FOLDER_MIME_TYPE,
            sizeBytes = size?.toLongOrNull() ?: 0L,
            modifiedTimeMs = parseIsoTimeMs(modifiedTime),
            md5Checksum = md5Checksum
        )
    }

    private fun parseIsoTimeMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
    }

    companion object {
        const val DEFAULT_BASE_API_URL = "https://www.googleapis.com/drive/v3"
        const val DEFAULT_BASE_UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val GOOGLE_APPS_PREFIX = "application/vnd.google-apps."
        private const val FILE_FIELDS = "id,name,parents,mimeType,md5Checksum,modifiedTime,size,trashed"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 3
    }
}
