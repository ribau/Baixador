package com.ribau.baixador

import org.junit.Assert.*
import org.junit.Test

class DownloadRulesTest {
    @Test fun extractsSharedLinkAndPreservesQuery() {
        assertEquals("https://www.youtube.com/watch?v=123&list=456",
            DownloadRules.extractUrl("Olha: https://www.youtube.com/watch?v=123&list=456\nCompartilhado"))
        assertEquals("https://instagram.com/reel/abc/", DownloadRules.extractUrl("(https://instagram.com/reel/abc/)."))
    }
    @Test fun rejectsNonWebLinksAndCredentials() {
        listOf("", "sem link", "file:///sdcard/test", "javascript:alert(1)",
            "https://", "https://user:pass@example.com/video").forEach {
            assertNull(DownloadRules.extractUrl(it))
        }
    }
    @Test fun capsEveryVideoFallback() {
        val selector = DownloadRules.formatSelector(720)
        assertEquals(4, Regex("height<=720").findAll(selector).count())
        assertTrue(selector.contains("+ba"))
        assertFalse(DownloadRules.formatSelector(0).contains("height"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeHeight() { DownloadRules.formatSelector(-1) }
}
