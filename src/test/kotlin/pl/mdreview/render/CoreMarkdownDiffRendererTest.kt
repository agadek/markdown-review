package pl.mdreview.render

import pl.mdreview.api.BlockStatus
import pl.mdreview.api.DiffOptions
import pl.mdreview.api.LineRange
import pl.mdreview.api.MarkdownDiffRenderer
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
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
}
