package com.obsidiandrivesync.domain.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreRuleMatcherTest {

    @Test
    fun `default rules ignore obsidian workspace, trash, git, and temp files`() {
        val matcher = IgnoreRuleMatcher(IgnoreRuleMatcher.DEFAULT_PATTERNS)

        assertTrue(matcher.isIgnored(".obsidian/workspace.json"))
        assertTrue(matcher.isIgnored(".obsidian/workspace-mobile.json"))
        assertTrue(matcher.isIgnored(".trash", isDirectory = true))
        assertTrue(matcher.isIgnored(".trash/OldNote.md"))
        assertTrue(matcher.isIgnored(".trash/Sub/Nested.md"))
        assertTrue(matcher.isIgnored(".git/HEAD"))
        assertTrue(matcher.isIgnored("Notes/draft.tmp"))
        assertTrue(matcher.isIgnored(".DS_Store"))
        assertTrue(matcher.isIgnored("Sub/.vaultsync.tmp"))

        assertFalse(matcher.isIgnored(".obsidian/app.json"))
        assertFalse(matcher.isIgnored(".obsidian/plugins/my-plugin/data.json"))
        assertFalse(matcher.isIgnored("Daily/2026-11-24.md"))
    }

    @Test
    fun `normalizes backslashes and leading slashes`() {
        val matcher = IgnoreRuleMatcher(listOf("Private/**", "*.bak", ".DS_Store"))
        assertTrue(matcher.isIgnored("\\Private\\secret.md"))
        assertTrue(matcher.isIgnored("/Notes/temp.bak"))
        assertTrue(matcher.isIgnored("temp.bak"))
        assertTrue(matcher.isIgnored("Sub/.DS_Store"))
        assertFalse(matcher.isIgnored("Notes/temp.md"))
    }

    @Test
    fun `trailing slash directory rule matches directory and nested files`() {
        val matcher = IgnoreRuleMatcher(listOf("Private/"))
        assertTrue(matcher.isIgnored("Private", isDirectory = true))
        assertTrue(matcher.isIgnored("Private/secret.md"))
        assertTrue(matcher.isIgnored("Private/Nested/deep.md"))
        assertFalse(matcher.isIgnored("Public/note.md"))
    }
}
