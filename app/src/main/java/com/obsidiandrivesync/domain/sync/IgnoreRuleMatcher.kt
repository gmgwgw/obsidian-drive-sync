package com.obsidiandrivesync.domain.sync

/**
 * Matches normalized vault-relative paths against glob-style ignore patterns.
 * Always excludes internal temporary sync files (.vaultsync.tmp).
 */
class IgnoreRuleMatcher(patterns: List<String>) {

    private val matchers: List<Regex> = patterns
        .asSequence()
        .map { it.trim().replace('\\', '/').trimStart('/') }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { patternToRegex(it) }
        .toList()

    fun isIgnored(relativePath: String, isDirectory: Boolean = false): Boolean {
        val normalized = normalizePath(relativePath)
        if (normalized.isEmpty()) return false

        val fileName = normalized.substringAfterLast('/')
        if (fileName == TEMP_SYNC_FILE_NAME || fileName.endsWith(".vaultsync.tmp")) {
            return true
        }

        return matchers.any { regex ->
            regex.matches(normalized) || (isDirectory && regex.matches("$normalized/"))
        }
    }

    companion object {
        const val TEMP_SYNC_FILE_NAME = ".vaultsync.tmp"

        val DEFAULT_PATTERNS: List<String> = listOf(
            ".obsidian/workspace.json",
            ".obsidian/workspace-mobile.json",
            ".trash/**",
            ".trash",
            ".git/**",
            ".git",
            "**/*.tmp",
            "**/.vaultsync.tmp",
            ".DS_Store",
            "Thumbs.db"
        )

        fun normalizePath(path: String): String {
            return path.replace('\\', '/')
                .trim()
                .trim('/')
                .replace(Regex("/+"), "/")
        }

        private fun patternToRegex(rawPattern: String): Regex {
            val pattern = rawPattern.trimEnd('/')
            val sb = StringBuilder("^")
            // Match basename patterns (no '/') at any directory depth, consistent with .gitignore.
            if (!pattern.contains('/')) {
                sb.append("(?:.*/)?")
            }
            var i = 0
            while (i < pattern.length) {
                when {
                    i + 2 < pattern.length && pattern[i] == '*' && pattern[i + 1] == '*' && pattern[i + 2] == '/' -> {
                        sb.append("(?:.*/)?")
                        i += 3
                    }
                    i + 1 < pattern.length && pattern[i] == '*' && pattern[i + 1] == '*' -> {
                        sb.append(".*")
                        i += 2
                    }
                    pattern[i] == '*' -> {
                        sb.append("[^/]*")
                        i++
                    }
                    pattern[i] == '?' -> {
                        sb.append("[^/]")
                        i++
                    }
                    else -> {
                        sb.append(Regex.escape(pattern[i].toString()))
                        i++
                    }
                }
            }
            // If a directory rule like ".trash" is given without /**, match both exact dir and contents.
            if (!rawPattern.contains('*') && !rawPattern.endsWith('/')) {
                sb.append("(?:/.*)?")
            }
            sb.append("$")
            return Regex(sb.toString())
        }
    }
}
