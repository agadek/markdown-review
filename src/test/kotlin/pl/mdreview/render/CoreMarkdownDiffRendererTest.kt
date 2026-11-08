package pl.mdreview.render

import pl.mdreview.api.BlockStatus
import pl.mdreview.api.DiffOptions
import pl.mdreview.api.DocumentFormat
import pl.mdreview.api.LineRange
import pl.mdreview.api.MarkdownDiffRenderer
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
import java.io.File
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CoreMarkdownDiffRendererTest {

    private val renderer = CoreMarkdownDiffRenderer()

    @Test
    fun `is registered for the service loader`() {
        val found = ServiceLoader.load(MarkdownDiffRenderer::class.java).firstOrNull()
        assertIs<CoreMarkdownDiffRenderer>(found)
    }

    @Test
    fun `block infos match the html`() {
        val result = renderer.render(RenderRequest("# A\n\nold\n", "# A\n\nnew text\n\nmore\n", ViewMode.SIDE_BY_SIDE))
        assertEquals(
            listOf(BlockStatus.EQUAL, BlockStatus.REMOVED, BlockStatus.ADDED, BlockStatus.ADDED),
            result.blocks.map { it.status },
        )
        assertEquals(LineRange(2, 3), result.blocks[1].left)
        assertEquals(null, result.blocks[1].right)
        assertEquals(LineRange(4, 5), result.blocks[3].right)
        result.blocks.forEach { b ->
            assertContains(result.bodyHtml, "class=\"mdr-row blk-${b.status.name.lowercase()}\" data-idx=\"${b.idx}\"")
        }
    }

    @Test
    fun `missing sides are empty documents`() {
        val added = renderer.render(RenderRequest(null, "# New\n", ViewMode.MERGED))
        assertEquals(listOf(BlockStatus.ADDED), added.blocks.map { it.status })
        val removed = renderer.render(RenderRequest("# Old\n", null, ViewMode.MERGED))
        assertEquals(listOf(BlockStatus.REMOVED), removed.blocks.map { it.status })
        assertTrue(renderer.render(RenderRequest(null, null, ViewMode.SIDE_BY_SIDE)).identical)
    }

    @Test
    fun `options reach the engine and the renderer`() {
        val ws = renderer.render(
            RenderRequest("a `x+y`", "a `x + y`", ViewMode.SIDE_BY_SIDE, DiffOptions(ignoreWhitespace = true)),
        )
        assertTrue(ws.identical)
        val left = (1..20).joinToString("\n\n") { "p$it" }
        val folded = renderer.render(RenderRequest(left, left.replace("p20", "x"), ViewMode.SIDE_BY_SIDE))
        assertContains(folded.bodyHtml, "<details")
        val open = renderer.render(
            RenderRequest(left, left.replace("p20", "x"), ViewMode.SIDE_BY_SIDE, DiffOptions(collapseUnchanged = false)),
        )
        assertTrue("<details" !in open.bodyHtml)
    }

    @Test
    fun `base uris reach the renderer`() {
        val result = renderer.render(
            RenderRequest("![x](x.png)", "![x](y.png)", ViewMode.SIDE_BY_SIDE, leftBaseUri = "file:///a/", rightBaseUri = "file:///b/"),
        )
        assertContains(result.bodyHtml, "src=\"file:///a/x.png\"")
        assertContains(result.bodyHtml, "src=\"file:///b/y.png\"")
    }

    @Test
    fun `a format without a front end falls back to raw paragraphs`() {
        val result = renderer.render(
            RenderRequest(
                "= Title\n\nOld paragraph.\n",
                "= Title\n\nNew paragraph.\n",
                ViewMode.SIDE_BY_SIDE,
                format = DocumentFormat.ASCIIDOC,
            ),
        )
        assertEquals(listOf(BlockStatus.EQUAL, BlockStatus.MODIFIED), result.blocks.map { it.status })
        assertContains(result.bodyHtml, "Old paragraph.")
        assertContains(result.bodyHtml, "New paragraph.")
        assertContains(result.bodyHtml, "class=\"mdr-block mdr-modified\"")
    }

    @Test
    fun `remote images in raw html load only when allowed`() {
        val md = "<img src=\"https://example.com/a.png\">\n"
        val blocked = renderer.render(RenderRequest("", md, ViewMode.MERGED))
        assertTrue("https://example.com/a.png" !in blocked.bodyHtml)
        val allowed = renderer.render(RenderRequest("", md, ViewMode.MERGED, DiffOptions(loadRemoteImages = true)))
        assertContains(allowed.bodyHtml, "src=\"https://example.com/a.png\"")
    }

    /**
     * The sanitizer change needed for AsciiDoc inline classes must not alter Markdown output. These snapshots were
     * generated from the commit before the AsciiDoc renderer.
     */
    @Test
    fun `31 html sanitizer output is unchanged by the AsciiDoc renderer`() {
        fun read(name: String): String {
            val url = javaClass.getResource(name) ?: error("Missing test resource $name")
            return File(url.toURI()).readBytes().toString(Charsets.UTF_8)
        }
        val old = read("/fixtures/31-html-sanitizer/old.md")
        val new = read("/fixtures/31-html-sanitizer/new.md")
        for (mode in ViewMode.entries) {
            val expected = read("/snapshots/31-html-sanitizer-${mode.name.lowercase()}.html")
            val actual = renderer.render(
                RenderRequest(old, new, mode, DiffOptions(collapseUnchanged = false)),
            ).bodyHtml
            assertEquals(expected, actual, mode.name)
        }
    }
}
