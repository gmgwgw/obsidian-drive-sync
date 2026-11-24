package com.obsidiandrivesync.data.local

import com.obsidiandrivesync.domain.model.LocalFileItem
import com.obsidiandrivesync.domain.sync.IgnoreRuleMatcher
import com.obsidiandrivesync.domain.sync.LocalVaultGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TrashStats(
    val fileCount: Int,
    val totalBytes: Long
)

class LocalVaultStorage(
    private val vaultRootPathProvider: () -> String,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) : LocalVaultGateway {

    private fun vaultRoot(): File {
        val path = vaultRootPathProvider()
        require(path.isNotBlank()) { "ローカル保管庫フォルダが設定されていません。" }
        return File(path).also {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }

    private fun resolveSafe(relativePath: String): File {
        val normalized = IgnoreRuleMatcher.normalizePath(relativePath)
        val root = vaultRoot().canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) {
            "不正なパスアクセスを拒否しました: $relativePath"
        }
        return target
    }

    override suspend fun listFiles(ignoreMatcher: IgnoreRuleMatcher): List<LocalFileItem> = withContext(Dispatchers.IO) {
        val root = vaultRoot()
        if (!root.exists() || !root.isDirectory) return@withContext emptyList()

        val result = mutableListOf<LocalFileItem>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                val isDir = child.isDirectory
                if (ignoreMatcher.isIgnored(rel, isDirectory = isDir)) {
                    continue
                }
                result += LocalFileItem(
                    relativePath = rel,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else child.length(),
                    lastModifiedMs = child.lastModified()
                )
                if (isDir) {
                    walk(child, rel)
                }
            }
        }
        walk(root, "")
        result
    }

    override fun computeMd5(relativePath: String): String? {
        val file = resolveSafe(relativePath)
        if (!file.exists() || !file.isFile) return null
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(32 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun getLocalItem(relativePath: String): LocalFileItem? {
        val file = resolveSafe(relativePath)
        if (!file.exists()) return null
        return LocalFileItem(
            relativePath = IgnoreRuleMatcher.normalizePath(relativePath),
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isDirectory) 0L else file.length(),
            lastModifiedMs = file.lastModified()
        )
    }

    override suspend fun ensureDirectory(relativePath: String) = withContext(Dispatchers.IO) {
        val dir = resolveSafe(relativePath)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("ディレクトリの作成に失敗しました: $relativePath")
        }
    }

    override suspend fun writeFileAtomically(
        relativePath: String,
        modifiedTimeMs: Long,
        writer: suspend (OutputStream) -> Unit
    ) = withContext(Dispatchers.IO) {
        val target = resolveSafe(relativePath)
        val parent = target.parentFile ?: vaultRoot()
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val tempFile = File(parent, ".${target.name}.${IgnoreRuleMatcher.TEMP_SYNC_FILE_NAME}")
        try {
            FileOutputStream(tempFile).use { fos ->
                writer(fos)
                fos.flush()
                fos.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("既存ファイルの置換に失敗しました: $relativePath")
            }
            if (!tempFile.renameTo(target)) {
                throw IllegalStateException("一時ファイルのリネームに失敗しました: $relativePath")
            }
            if (modifiedTimeMs > 0L) {
                target.setLastModified(modifiedTimeMs)
            }
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    override fun openInputStream(relativePath: String): InputStream {
        val file = resolveSafe(relativePath)
        return FileInputStream(file)
    }

    override suspend fun renameFile(fromRelativePath: String, toRelativePath: String): LocalFileItem =
        withContext(Dispatchers.IO) {
            val src = resolveSafe(fromRelativePath)
            val dst = resolveSafe(toRelativePath)
            dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
            if (!src.renameTo(dst)) {
                throw IllegalStateException("リネームに失敗しました: $fromRelativePath -> $toRelativePath")
            }
            LocalFileItem(
                relativePath = IgnoreRuleMatcher.normalizePath(toRelativePath),
                isDirectory = dst.isDirectory,
                sizeBytes = if (dst.isDirectory) 0L else dst.length(),
                lastModifiedMs = dst.lastModified()
            )
        }

    override suspend fun deleteItem(relativePath: String, isDirectory: Boolean, moveToTrash: Boolean) =
        withContext(Dispatchers.IO) {
            val target = resolveSafe(relativePath)
            if (!target.exists()) return@withContext

            if (isDirectory) {
                // Only remove directory if empty when deleting directories safely
                if (target.listFiles().isNullOrEmpty()) {
                    target.delete()
                }
                return@withContext
            }

            if (moveToTrash) {
                moveFileToObsidianTrash(target, relativePath)
            } else {
                target.delete()
            }
        }

    private fun moveFileToObsidianTrash(source: File, relativePath: String) {
        val root = vaultRoot()
        val trashRoot = File(root, TRASH_DIR_NAME)
        val normalized = IgnoreRuleMatcher.normalizePath(relativePath)
        var dest = File(trashRoot, normalized)
        dest.parentFile?.let { if (!it.exists()) it.mkdirs() }

        if (dest.exists()) {
            val ts = TRASH_COLLISION_FORMATTER.format(Instant.ofEpochMilli(clockMs()))
            val name = dest.name
            val dot = name.lastIndexOf('.')
            val uniqueName = if (dot > 0) {
                "${name.substring(0, dot)} ($ts)${name.substring(dot)}"
            } else {
                "$name ($ts)"
            }
            dest = File(dest.parentFile, uniqueName)
        }

        val moved = source.renameTo(dest)
        if (!moved) {
            source.copyTo(dest, overwrite = true)
            source.delete()
        }
        // Mark deletion timestamp so trash retention policy measures from when it entered .trash
        dest.setLastModified(clockMs())
    }

    override suspend fun purgeExpiredTrash(retentionDays: Int): Int = withContext(Dispatchers.IO) {
        if (retentionDays <= 0) return@withContext 0
        val trashRoot = File(vaultRoot(), TRASH_DIR_NAME)
        if (!trashRoot.exists() || !trashRoot.isDirectory) return@withContext 0

        val cutoffMs = clockMs() - (retentionDays.toLong() * MILLIS_PER_DAY)
        var deletedCount = 0

        fun cleanDir(dir: File): Boolean {
            val children = dir.listFiles() ?: return true
            for (child in children) {
                if (child.isDirectory) {
                    val emptyAfter = cleanDir(child)
                    if (emptyAfter) {
                        child.delete()
                    }
                } else {
                    if (child.lastModified() <= cutoffMs) {
                        if (child.delete()) {
                            deletedCount++
                        }
                    }
                }
            }
            return dir.listFiles().isNullOrEmpty()
        }

        cleanDir(trashRoot)
        deletedCount
    }

    suspend fun getTrashStats(): TrashStats = withContext(Dispatchers.IO) {
        val rootPath = vaultRootPathProvider()
        if (rootPath.isBlank()) return@withContext TrashStats(0, 0L)
        val trashRoot = File(rootPath, TRASH_DIR_NAME)
        if (!trashRoot.exists() || !trashRoot.isDirectory) return@withContext TrashStats(0, 0L)

        var count = 0
        var bytes = 0L
        trashRoot.walkTopDown().forEach { file ->
            if (file.isFile) {
                count++
                bytes += file.length()
            }
        }
        TrashStats(count, bytes)
    }

    suspend fun emptyTrashNow(): Int = withContext(Dispatchers.IO) {
        val rootPath = vaultRootPathProvider()
        if (rootPath.isBlank()) return@withContext 0
        val trashRoot = File(rootPath, TRASH_DIR_NAME)
        if (!trashRoot.exists() || !trashRoot.isDirectory) return@withContext 0

        var deleted = 0
        trashRoot.walkBottomUp().forEach { file ->
            if (file != trashRoot) {
                val wasFile = file.isFile
                if (file.delete() && wasFile) {
                    deleted++
                }
            }
        }
        deleted
    }

    companion object {
        const val TRASH_DIR_NAME = ".trash"
        private const val MILLIS_PER_DAY = 86_400_000L
        private val TRASH_COLLISION_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
                .withZone(ZoneId.systemDefault())
    }
}
