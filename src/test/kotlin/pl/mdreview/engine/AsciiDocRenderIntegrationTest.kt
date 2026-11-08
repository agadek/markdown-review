package pl.mdreview.engine

import org.asciidoctor.Asciidoctor
import pl.mdreview.api.BlockStatus
import pl.mdreview.api.DocumentFormat
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
import pl.mdreview.engine.asciidoc.AsciidoctorProvider
import pl.mdreview.render.CoreMarkdownDiffRenderer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Renders the real AsciiDoc fixture pairs through the shared generator and checks that every modified block shows
 * its change and that no Asciidoctor block markup leaks into the page (P4.4).
 */
class AsciiDocRenderIntegrationTest {

    private val provider = object : AsciidoctorProvider {
        private val instance: Asciidoctor by lazy { Asciidoctor.Factory.create() }
        override fun asciidoctor(): Asciidoctor = instance
    }

    private val renderer = CoreMarkdownDiffRenderer(provider)

    @AfterTest
    fun stopAsciidoctor() {
        runCatching { provider.asciidoctor().shutdown() }
        runCatching { provider.asciidoctor().close() }
    }

    private fun read(case: String, name: String): String {
        val url = javaClass.getResource("/fixtures/$case/$name") ?: error("Missing fixture $case/$name")
        return File(url.toURI()).readBytes().toString(Charsets.UTF_8)
    }

    private fun cases(): List<String> =
        File(javaClass.getResource("/fixtures")!!.toURI()).listFiles()!!
            .map { it.name }
            .filter { it.matches(Regex("(5[0-9]|6[0-9])-adoc-.*")) }
            .sorted()

    @Test
    fun `every AsciiDoc fixture renders with a mark on every modified block`() {
        val cases = cases()
        assertTrue(cases.size >= 18, "expected all AsciiDoc fixtures, found $cases")
        for (case in cases) {
            for (mode in ViewMode.entries) {
                val rendered = renderer.render(
                    RenderRequest(read(case, "old.adoc"), read(case, "new.adoc"), mode, format = DocumentFormat.ASCIIDOC),
                )
                val html = rendered.bodyHtml
                for (bad in ASCIIDOC_BLOCK_MARKUP) {
                    assertFalse(bad in html, "$case ${mode.name}: raw Asciidoctor block markup $bad")
                }
                for (block in rendered.blocks.filter { it.status == BlockStatus.MODIFIED }) {
                    val segment = rowSegment(html, block.idx)
                    assertTrue(
                        MARKER.containsMatchIn(segment),
                        "$case ${mode.name}: modified block ${block.idx} has no visible mark:\n$segment",
                    )
                }
            }
        }
    }

    private fun rowSegment(html: String, idx: Int): String {
        val start = Regex("<div class=\"mdr-(?:row|item)[^\"]*\"[^>]*data-idx=\"$idx\"").find(html)?.range?.first
            ?: return ""
        val next = Regex("<div class=\"mdr-(?:row|item)[^\"]*\"[^>]*data-idx=").find(html, start + 1)?.range?.first ?: html.length
        return html.substring(start, next)
    }

    private companion object {
        val MARKER = Regex("mdr-del|mdr-ins|mdr-(?:link|img)-changed|data-change-note|mdr-moved|mdr-(?:mermaid|math)-row|mdr-math-error")

        val ASCIIDOC_BLOCK_MARKUP = listOf(
            "class=\"admonitionblock\"",
            "class=\"listingblock\"",
            "class=\"literalblock\"",
            "class=\"imageblock\"",
            "class=\"exampleblock\"",
            "class=\"sidebarblock\"",
            "class=\"quoteblock\"",
            "class=\"verseblock\"",
            "class=\"openblock\"",
            "class=\"tableblock\"",
            "class=\"ulist\"",
            "class=\"olist\"",
            "class=\"dlist\"",
            "class=\"paragraph\"",
        )
    }
}
