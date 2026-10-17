package pl.mdreview.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SizeLimitTest {

    @Test
    fun `missing and small texts are within the limit`() {
        assertFalse(SizeLimit.DEFAULT.exceeds(null))
        assertFalse(SizeLimit.DEFAULT.exceeds(""))
        assertFalse(SizeLimit.DEFAULT.exceeds("# Title\n\ntext\n"))
    }

    @Test
    fun `line limit counts lines, not separators`() {
        val limit = SizeLimit(maxLines = 3, maxBytes = 1_000)
        assertFalse(limit.exceeds("a\nb\nc"))
        assertTrue(limit.exceeds("a\nb\nc\nd"))
    }

    @Test
    fun `byte limit counts utf-8 bytes`() {
        val limit = SizeLimit(maxLines = 100, maxBytes = 6)
        assertFalse(limit.exceeds("abcdef"))
        assertTrue(limit.exceeds("abcdefg"))
        // Three chars, two bytes each in UTF-8.
        assertFalse(limit.exceeds("ąęś"))
        assertTrue(limit.exceeds("ąęśź"))
        // An emoji is a surrogate pair: four bytes.
        assertFalse(limit.exceeds("\uD83D\uDE00ab"))
        assertTrue(limit.exceeds("\uD83D\uDE00abc"))
    }

    @Test
    fun `default limit is 20,000 lines or 2 MB`() {
        assertFalse(SizeLimit.DEFAULT.exceeds("x\n".repeat(19_999)))
        assertTrue(SizeLimit.DEFAULT.exceeds("x\n".repeat(20_000)))
        assertTrue(SizeLimit.DEFAULT.exceeds("x".repeat(2 * 1024 * 1024 + 1)))
    }
}
