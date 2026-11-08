package pl.mdreview.engine.asciidoc

import org.asciidoctor.Asciidoctor
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffOptions
import pl.mdreview.engine.HtmlFragment
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One minimal document per row of the ADR 0005 table, pinning the extracted `(kind, lines, plainText, context)`.
 */
class AsciiDocBlockSourceTest {

    private val source = AsciiDocBlockSource(TestProvider)

    private fun extract(text: String): List<Block> = source.extract(text.trimIndent(), DiffOptions())

    @Test
    fun `document title author revision and attributes`() {
        val blocks = extract(
            """
            = Guide
            Jane Doe <jane@example.com>
            v1.2, 2024-01-02: First release
            :product: Widget

            Body.
            """,
        )
        assertEquals(
            listOf(BlockKind.HEADING, BlockKind.ATTRIBUTE, BlockKind.ATTRIBUTE, BlockKind.ATTRIBUTE, BlockKind.PARAGRAPH),
            blocks.map { it.kind },
        )
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 5 to 6), blocks.map { it.source.startLine to it.source.endLine })
        assertEquals("Guide", blocks[0].plainText)
        assertEquals(1, blocks[0].level)
        assertEquals(
            listOf(
                "Jane Doe <jane@example.com>",
                "v1.2, 2024-01-02: First release",
                ":product: Widget",
            ),
            blocks.subList(1, 4).map { it.plainText },
        )
        assertEquals("Body.", blocks[4].plainText)
    }

    @Test
    fun `section levels and discrete headings`() {
        val blocks = extract(
            """
            = Guide

            == Section

            [discrete]
            === Discrete

            Text.
            """,
        )
        assertEquals(listOf(1, 2, 3), blocks.filter { it.kind == BlockKind.HEADING }.map { it.level })
        assertEquals(listOf("Guide", "Section", "Discrete"), blocks.filter { it.kind == BlockKind.HEADING }.map { it.plainText })
        assertEquals(listOf(0, 2, 5), blocks.filter { it.kind == BlockKind.HEADING }.map { it.source.startLine })
        // The [discrete] line is a prefix, not a row.
        assertTrue(blocks.none { it.source.startLine == 4 })
    }

    @Test
    fun `paragraphs and literal paragraphs`() {
        val blocks = extract(
            """
            = T

            Normal *bold* text.

             A literal
             paragraph.
            """,
        )
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.CODE_LINE, BlockKind.CODE_LINE), blocks.map { it.kind })
        assertEquals("Normal bold text.", blocks[1].plainText)
        assertEquals(listOf(4 to 5, 5 to 6), blocks.drop(2).map { it.source.startLine to it.source.endLine })
        assertEquals(listOf("A literal", "paragraph."), blocks.drop(2).map { it.plainText })
    }

    @Test
    fun `an admonition paragraph is one alert with the body text`() {
        val blocks = extract(
            """
            = T

            NOTE: Some *bold* text.
            """,
        )
        val alert = blocks.single { it.kind == BlockKind.ALERT }
        assertEquals(listOf(BlockKind.HEADING, BlockKind.ALERT), blocks.filter { it.kind != BlockKind.PARAGRAPH }.map { it.kind })
        assertEquals("Some bold text.", alert.plainText)
        assertEquals(2, alert.source.startLine)
        assertEquals(listOf("admonition"), alert.context.containers.map { it.kind })
        assertEquals("NOTE", alert.context.containers.single().type)
        assertContains(alert.key, "alert=NOTE")
        val markup = (alert.content as BlockContent.Html).parts.filterIsInstance<HtmlFragment.Markup>().map { it.html }
        assertTrue(markup.contains("<strong>"))
    }

    @Test
    fun `a delimited admonition is a container without its own row`() {
        val blocks = extract(
            """
            = T

            [WARNING]
            ====
            First.

            Second.
            ====
            """,
        )
        assertEquals(listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), blocks.map { it.kind })
        assertEquals(
            listOf(
                listOf("admonition" to "WARNING"),
                listOf("admonition" to "WARNING"),
            ),
            blocks.drop(1).map { b -> b.context.containers.map { it.kind to it.type } },
        )
        assertEquals(listOf(4, 6), blocks.drop(1).map { it.source.startLine })
    }

    @Test
    fun `containers wrap their child blocks and block titles keep the frame`() {
        val blocks = extract(
            """
            = T

            .Intro
            ====
            Outer.

            ****
            Inner.
            ****
            ====
            """,
        )
        assertEquals(listOf(BlockKind.HEADING, BlockKind.BLOCK_TITLE, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), blocks.map { it.kind })
        assertEquals("Intro", blocks[1].plainText)
        assertEquals(listOf("example"), blocks[1].context.containers.map { it.kind })
        assertEquals("Intro", blocks[1].context.containers.single().title)
        assertEquals(listOf("example"), blocks[2].context.containers.map { it.kind })
        assertEquals(listOf("example", "sidebar"), blocks[3].context.containers.map { it.kind })
        assertEquals(listOf(2, 4, 7), blocks.drop(1).map { it.source.startLine })
    }

    @Test
    fun `lists carry depth ordered number checklist and callout state`() {
        val blocks = extract(
            """
            = T

            * one
            ** nested
            * [x] done

            . first
            . second

            <1> callout one
            <2> callout two

            == Next

            term:: description
            """,
        )
        val bullets = blocks.filter { it.kind == BlockKind.LIST_ITEM && it.context.listDepth > 0 && !it.context.ordered && it.context.containers.none { c -> c.kind == "description" } }
        assertEquals(listOf("one", "nested", "done"), bullets.map { it.plainText })
        assertEquals(listOf(1, 2, 1), bullets.map { it.context.listDepth })
        assertContains(bullets[0].key, "l1")
        assertContains(bullets[1].key, "l2")
        assertContains(bullets[2].key, "task=checked")
        assertContains(bullets[2].key, "[x]")

        val ordered = blocks.filter { it.kind == BlockKind.LIST_ITEM && it.context.ordered && it.context.containers.none { c -> c.kind == "callout" } }
        assertEquals(listOf("first", "second"), ordered.map { it.plainText })
        assertEquals(listOf(1, 2), ordered.map { it.context.number })
        assertContains(ordered[0].key, "ol")

        val callouts = blocks.filter { it.context.containers.any { c -> c.kind == "callout" } }
        assertEquals(listOf("callout one", "callout two"), callouts.map { it.plainText })
        assertEquals(listOf(1, 2), callouts.map { it.context.number })
        assertTrue(callouts.all { it.context.ordered })

        val term = blocks.single { it.kind == BlockKind.DESCRIPTION_TERM }
        val description = blocks.last()
        assertEquals("term", term.plainText)
        assertEquals("description", description.plainText)
        assertEquals(term.source.startLine, description.source.startLine)
        assertEquals(listOf("description"), description.context.containers.map { it.kind })
        assertEquals("term", term.rawText)
        assertEquals("description", description.rawText)
    }

    @Test
    fun `tables produce one row per row with th and td wrappers`() {
        val blocks = extract(
            """
            = T

            [cols="1,2",options="header"]
            |===
            | Name | Notes

            | A | plain
            | B a| *bold* text
            |===
            """,
        )
        val rows = blocks.filter { it.kind == BlockKind.TABLE_ROW }
        assertEquals(listOf("Name | Notes", "A | plain", "B | bold text"), rows.map { it.plainText })
        assertEquals(listOf(4, 6, 7), rows.map { it.source.startLine })
        assertTrue(rows[0].key.startsWith("th:"))
        assertTrue(rows[1].key.startsWith("td:"))
        assertTrue(rows[2].key.startsWith("td:"))
        assertEquals("th", firstMarkup(rows[0]).tag)
        assertEquals("td", firstMarkup(rows[1]).tag)
        assertEquals("td", firstMarkup(rows[2]).tag)
        assertContains(rows[2].rawText, "a| *bold* text")
        assertContains(rows[2].key, "*bold*")
    }

    @Test
    fun `a multi-line table cell covers its lines and reads as text`() {
        val blocks = extract(
            """
            = T

            |===
            | A a| line one

            line two
            | B | x
            |===
            """,
        )
        val rows = blocks.filter { it.kind == BlockKind.TABLE_ROW }
        assertEquals(listOf("A | line one line two", "B | x"), rows.map { it.plainText })
        assertEquals(3 to 6, rows[0].source.startLine to rows[0].source.endLine)
        assertEquals(6 to 7, rows[1].source.startLine to rows[1].source.endLine)
    }

    @Test
    fun `listing source and literal blocks are a fence plus code lines`() {
        val blocks = extract(
            """
            = T

            [source,java]
            ----
            one
            two // <1>
            ----

            ....
            literal
            ....
            """,
        )
        val fences = blocks.filter { it.kind == BlockKind.CODE_FENCE }
        assertEquals(listOf("source,java", "literal"), fences.map { it.plainText })
        assertEquals(listOf("java", null), fences.map { it.language })
        assertEquals(listOf(2, 8), fences.map { it.source.startLine })
        val code = blocks.filter { it.kind == BlockKind.CODE_LINE }
        assertEquals(listOf("one", "two // <1>", "literal"), code.map { it.plainText })
        assertEquals(listOf(4, 5, 9), code.map { it.source.startLine })
        assertContains(fences[0].key, "language=java")
        assertContains(code[1].plainText, "<1>")
    }

    @Test
    fun `images have a target alt text and a title`() {
        val blocks = extract(
            """
            = T

            .Screenshot
            image::pic.png[Alt text,320,200]

            image::other.png[Other]
            """,
        )
        val images = blocks.filter { it.kind == BlockKind.IMAGE }
        assertEquals(listOf("pic.png", "other.png"), images.map { it.target })
        assertEquals(listOf("Alt text", "Other"), images.map { it.plainText })
        assertEquals("Screenshot", images[0].title)
        assertContains(images[0].key, "pic.png")
        assertContains(images[0].key, "Alt text")
        assertEquals(BlockKind.BLOCK_TITLE, blocks.first { it.kind == BlockKind.BLOCK_TITLE }.kind)
        val img = HtmlFragment.parse(images[0].content.let { (it as BlockContent.Html).parts.joinToString("") { p -> if (p is HtmlFragment.Markup) p.html else (p as HtmlFragment.TextRun).text } }, true)
        assertEquals("img", img.filterIsInstance<HtmlFragment.Markup>().first().tag)
    }

    @Test
    fun `thematic breaks page breaks and passthrough blocks`() {
        val blocks = extract(
            """
            = T

            '''

            <<<

            ++++
            <p>Raw <strong>HTML</strong>.</p>
            ++++
            """,
        )
        assertEquals(listOf(BlockKind.HEADING, BlockKind.THEMATIC_BREAK, BlockKind.THEMATIC_BREAK, BlockKind.HTML), blocks.map { it.kind })
        assertEquals("'''", blocks[1].rawText)
        assertEquals("<<<", blocks[2].rawText)
        assertContains(blocks[1].key, "thematic-break")
        assertContains(blocks[2].key, "page-break")
        assertEquals("Raw HTML.", blocks[3].plainText)
        assertContains(blocks[3].content.let { (it as BlockContent.Html).parts.filterIsInstance<HtmlFragment.Markup>().joinToString("") { p -> p.html } }, "<strong>")
    }

    @Test
    fun `comments includes and conditionals come from the scanner`() {
        val blocks = extract(
            """
            = T

            :flag:

            // line comment

            ////
            Block comment.
            ////

            include::chapters/intro.adoc[]

            ifdef::flag[]
            Kept.
            endif::[]
            """,
        )
        val comments = blocks.filter { it.kind == BlockKind.HTML_COMMENT }
        assertEquals(
            listOf("line comment", "Block comment.", "ifdef::flag[]", "endif::[]"),
            comments.map { it.plainText },
        )
        val include = blocks.single { it.kind == BlockKind.INCLUDE }
        assertEquals("chapters/intro.adoc", include.target)
        assertEquals("include::chapters/intro.adoc[]", include.plainText)
        // SECURE mode's generated link paragraph for the include is dropped; the directive appears once.
        assertEquals(1, blocks.count { it.source.startLine == include.source.startLine })
        val kept = blocks.single { it.kind == BlockKind.PARAGRAPH }
        assertEquals("Kept.", kept.plainText)
        // The paragraph before endif is reported on the endif line; the scanner anchors it above.
        assertEquals(13, kept.source.startLine)
        assertTrue(comments.last().source.startLine == 14)
    }

    @Test
    fun `a parser failure falls back to raw paragraphs with correct lines`() {
        val broken = AsciiDocBlockSource(object : AsciidoctorProvider {
            override fun asciidoctor(): Asciidoctor = throw IllegalStateException("no Asciidoctor")
        })
        val blocks = broken.extract("One\n\nTwo\nThree\n", DiffOptions())
        assertEquals(listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), blocks.map { it.kind })
        assertEquals(listOf(0 to 1, 2 to 4), blocks.map { it.source.startLine to it.source.endLine })
        assertEquals(listOf("One", "Two\nThree"), blocks.map { it.plainText })
    }

    private fun firstMarkup(block: Block): HtmlFragment.Markup =
        assertNotNull((block.content as BlockContent.Html).parts.filterIsInstance<HtmlFragment.Markup>().firstOrNull())

    private object TestProvider : AsciidoctorProvider {
        private val instance: Asciidoctor by lazy { Asciidoctor.Factory.create() }
        override fun asciidoctor(): Asciidoctor = instance
    }
}
