package pl.mdreview.render

import pl.mdreview.api.ViewMode
import pl.mdreview.engine.DiffStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renderer-level checks for the AsciiDoc fixtures. P4.3's AsciiDoc front end is not on this branch, so the blocks are
 * built by [AsciiDocTestData] from the same structure the fixtures describe.
 */
class AsciiDocFixtureRenderTest {

    private fun read(path: String): String {
        val url = javaClass.getResource(path) ?: error("Missing test resource $path")
        return File(url.toURI()).readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun snapshot(case: String, mode: ViewMode): String =
        read("/snapshots/$case-${mode.name.lowercase()}.html")

    private fun render(case: String, mode: ViewMode): String {
        val (left, right) = when (case) {
            "56-adoc-containers" -> AsciiDocTestData.containersFixture()
            "61-adoc-links-images" -> AsciiDocTestData.linksFixture()
            else -> error("Unknown fixture $case")
        }
        val options = RenderOptions(
            mode = mode,
            collapseUnchanged = false,
            leftBaseUri = "file:///repo/docs/",
            rightBaseUri = "file:///repo/docs/",
        )
        return AsciiDocTestData.render(left, right, mode, options)
    }

    /** Each changed row/item of these fixtures is a modified block; it must carry a word mark or a change note. */
    private fun assertEveryChangeIsVisible(case: String, html: String, mode: ViewMode) {
        val starts = Regex("<div class=\"mdr-(?:row|item) blk-modified\"[^>]*>").findAll(html).toList()
        val (left, right) = when (case) {
            "56-adoc-containers" -> AsciiDocTestData.containersFixture()
            else -> AsciiDocTestData.linksFixture()
        }
        val diff = AsciiDocTestData.diff(left, right)
        val expected = diff.blocks.count { it.status == DiffStatus.MODIFIED }
        assertEquals(expected, starts.size, "$mode: one rendered row/item per modified block")
        starts.forEachIndexed { index, start ->
            val end = starts.getOrNull(index + 1)?.range?.first ?: html.length
            val segment = html.substring(start.range.first, end)
            assertTrue(
                Regex("mdr-del|mdr-ins|mdr-(?:link|img)-changed|data-change-note").containsMatchIn(segment),
                "$mode: changed block ${start.value} has no visible mark:\n$segment",
            )
        }
    }

    @Test
    fun `56 adoc containers renders in both modes with a mark on every change`() {
        for (mode in ViewMode.entries) {
            val html = render("56-adoc-containers", mode)
            assertEquals(snapshot("56-adoc-containers", mode), html, mode.name)
            assertEveryChangeIsVisible("56-adoc-containers", html, mode)
        }
    }

    @Test
    fun `61 adoc links and images renders in both modes with a mark on every change`() {
        for (mode in ViewMode.entries) {
            val html = render("61-adoc-links-images", mode)
            assertEquals(snapshot("61-adoc-links-images", mode), html, mode.name)
            assertEveryChangeIsVisible("61-adoc-links-images", html, mode)
        }
    }
}
