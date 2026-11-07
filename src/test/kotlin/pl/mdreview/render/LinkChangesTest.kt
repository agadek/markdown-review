package pl.mdreview.render

import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffEngine
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.MarkdownBlockSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkChangesTest {

    private fun block(html: String): Block = MarkdownBlockSource.extract("$html\n").single()

    private fun read(case: String, name: String): String {
        val url = javaClass.getResource("/fixtures/$case/$name") ?: error("Missing fixture $case/$name")
        return File(url.toURI()).readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `html link target change behind equal text is marked`() {
        val left = block("<p>See <a href=\"https://example.com/old\">docs</a> and <a href=\"https://example.com/same\">same</a>.</p>")
        val right = block("<p>See <a href=\"https://example.com/new\">docs</a> and <a href=\"https://example.com/same\">same</a>.</p>")
        assertIs<BlockContent.Html>(left.content)

        // Pair by text and occurrence: only the first "docs" link changed its target.
        val changes = LinkChanges.between(left, right)
        assertEquals(mapOf(0 to "https://example.com/new"), changes.leftHtml)
        assertEquals(mapOf(0 to "https://example.com/old"), changes.rightHtml)

        val html = DiffHtmlRenderer().renderBody(DiffEngine().diff(listOf(left), listOf(right)))
        assertContains(
            html,
            "<a href=\"https://example.com/old\" class=\"mdr-link-changed\" " +
                "title=\"New URL: https://example.com/new\">docs</a>",
        )
        assertContains(
            html,
            "<a href=\"https://example.com/new\" class=\"mdr-link-changed\" " +
                "title=\"Previous URL: https://example.com/old\">docs</a>",
        )
        // The unchanged "same" link is not marked.
        assertContains(html, "<a href=\"https://example.com/same\">same</a>")
    }

    @Test
    fun `html image alt text pairs a changed source`() {
        val left = block("<p><img src=\"old.png\" alt=\"Logo\"> and <img src=\"same.png\" alt=\"Other\"></p>")
        val right = block("<p><img src=\"new.png\" alt=\"Logo\"> and <img src=\"same.png\" alt=\"Other\"></p>")

        val changes = LinkChanges.between(left, right)
        assertEquals(mapOf(0 to "new.png"), changes.leftHtml)
        assertEquals(mapOf(0 to "old.png"), changes.rightHtml)

        val html = DiffHtmlRenderer().renderBody(DiffEngine().diff(listOf(left), listOf(right)))
        assertContains(html, "<img src=\"new.png\" alt=\"Logo\" class=\"mdr-img-changed\" title=\"Previous URL: old.png\">")
        assertContains(html, "<img src=\"old.png\" alt=\"Logo\" class=\"mdr-img-changed\" title=\"New URL: new.png\">")
    }

    @Test
    fun `fixture 29 html blocks have html content and no link changes`() {
        val left = MarkdownBlockSource.extract(read("29-html-blocks", "old.md"))
        val right = MarkdownBlockSource.extract(read("29-html-blocks", "new.md"))
        val modified = DiffEngine().diff(left, right).blocks.filter { it.status == DiffStatus.MODIFIED }
        assertEquals(2, modified.size)
        for (block in modified) {
            assertIs<BlockContent.Html>(block.left!!.content)
            assertIs<BlockContent.Html>(block.right!!.content)
            assertTrue(LinkChanges.between(block.left, block.right).leftHtml.isEmpty())
            assertTrue(LinkChanges.between(block.left, block.right).rightHtml.isEmpty())
        }
    }

    @Test
    fun `markdown blocks carry commonmark, html or raw content`() {
        val blocks = MarkdownBlockSource.extract("# Title\n\n<p>Hi</p>\n\n```\ncode\n```\n")
        assertIs<BlockContent.Commonmark>(blocks[0].content)
        assertEquals(BlockKind.HEADING, blocks[0].kind)
        assertIs<BlockContent.Html>(blocks[1].content)
        assertEquals(BlockKind.HTML, blocks[1].kind)
        assertTrue(blocks.drop(2).all { it.content == BlockContent.Raw })
        assertFalse(blocks.any { it.kind == BlockKind.CODE_FENCE && it.content != BlockContent.Raw })
    }
}
