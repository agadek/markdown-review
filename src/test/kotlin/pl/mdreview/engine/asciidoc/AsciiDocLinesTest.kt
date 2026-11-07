package pl.mdreview.engine.asciidoc

import pl.mdreview.engine.BlockKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsciiDocLinesTest {

    private fun lines(text: String) = AsciiDocLines(text.trimIndent())

    @Test
    fun `matches nested delimiters with the line of every close`() {
        val source = lines(
            """
            ====
            Example
            ****
            Sidebar
            ****
            ====
            """,
        )
        val outer = assertNotNull(source.openingDelimiter(0))
        val inner = assertNotNull(source.openingDelimiter(2))
        assertEquals("example", outer.kind)
        assertEquals("sidebar", inner.kind)
        assertEquals(5, outer.closeLine)
        assertEquals(4, inner.closeLine)
        assertEquals(0, assertNotNull(source.closingDelimiter(5)).startLine)
        assertEquals(setOf(0, 2, 4, 5), source.delimiterLines)
    }

    @Test
    fun `a longer run also closes a delimiter`() {
        val source = lines(
            """
            ====
            Example
            =====
            Text after.
            """,
        )
        assertEquals(2, assertNotNull(source.openingDelimiter(0)).closeLine)
        assertNull(source.openingDelimiter(2))
    }

    @Test
    fun `finds the header and its attribute lines`() {
        val source = lines(
            """
            = Guide
            Jane Doe <jane@example.com>
            v1.2, 2024-01-02: First release
            :product: Widget
            :flag:

            Body.
            """,
        )
        assertEquals(0, source.titleLine)
        assertEquals(5, source.headerEnd)
        val heading = assertNotNull(source.titleRow())
        assertEquals(BlockKind.HEADING, heading.kind)
        assertEquals("Guide", heading.plainText)
        assertEquals(1, heading.level)
        val attributes = source.rows.filter { it.kind == BlockKind.ATTRIBUTE }
        assertEquals(
            listOf(
                "Jane Doe <jane@example.com>",
                "v1.2, 2024-01-02: First release",
                ":product: Widget",
                ":flag:",
            ),
            attributes.map { it.plainText },
        )
        assertEquals(listOf(1, 2, 3, 4), attributes.map { it.startLine })
    }

    @Test
    fun `does not scan comments attributes and includes inside a listing`() {
        val source = lines(
            """
            ----
            // not a comment
            :not-an-attribute: x
            include::missing.adoc[]
            ////
            ----
            // a real comment
            """,
        )
        assertEquals(listOf(BlockKind.HTML_COMMENT), source.rows.map { it.kind })
        assertEquals(6, source.rows.single().startLine)
        assertEquals("a real comment", source.rows.single().plainText)
        assertEquals(setOf(0, 5), source.delimiterLines)
        assertTrue(source.includeLines.isEmpty())
    }

    @Test
    fun `a block comment is one comment row with its inner text`() {
        val source = lines(
            """
            ////
            Line one.
            Line two.
            ////
            Text.
            """,
        )
        val comment = source.rows.single()
        assertEquals(BlockKind.HTML_COMMENT, comment.kind)
        assertEquals(0, comment.startLine)
        assertEquals(4, comment.endLine)
        assertEquals("Line one.\nLine two.", comment.plainText)
        assertEquals(setOf(0, 3), source.delimiterLines)
    }

    @Test
    fun `records prefix lines above the block they belong to`() {
        val source = lines(
            """
            .Title
            [source,java]
            ----
            code
            ----
            """,
        )
        assertEquals(listOf(BlockKind.BLOCK_TITLE), source.rows.map { it.kind })
        assertEquals(0, source.rows.single().startLine)
        assertEquals(listOf(1), source.prefixesBefore(2))
        assertEquals(setOf(1), source.prefixLines)
        assertEquals(4, assertNotNull(source.openingDelimiter(2)).closeLine)
    }

    @Test
    fun `records body attributes includes and preprocessor directives`() {
        val source = lines(
            """
            = T

            :status: beta

            ifdef::flag[]
            include::chapters/intro.adoc[]
            endif::[]
            """,
        )
        val rows = source.rows
        assertEquals(
            listOf(
                BlockKind.HEADING to 0,
                BlockKind.ATTRIBUTE to 2,
                BlockKind.HTML_COMMENT to 4,
                BlockKind.INCLUDE to 5,
                BlockKind.HTML_COMMENT to 6,
            ),
            rows.map { it.kind to it.startLine },
        )
        assertEquals("chapters/intro.adoc", rows[3].target)
        assertEquals(setOf(4, 6), source.directiveLines)
        assertEquals(setOf(5), source.includeLines)
    }

    @Test
    fun `matches a table delimiter`() {
        val source = lines(
            """
            |===
            | A | B
            |===
            """,
        )
        val table = assertNotNull(source.openingDelimiter(0))
        assertEquals("table", table.kind)
        assertEquals(2, table.closeLine)
    }
}
