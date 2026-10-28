package pl.mdreview.ide

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
import pl.mdreview.render.CoreMarkdownDiffRenderer
import java.awt.Color
import java.awt.Font

class HtmlPageTest : BasePlatformTestCase() {

    private fun page(left: String, right: String): String =
        HtmlPage.build(CoreMarkdownDiffRenderer().render(RenderRequest(left, right, ViewMode.SIDE_BY_SIDE)), "void 0", 0)

    private val diagram = "```mermaid\nflowchart LR\n    A --> B\n```\n"

    fun `test page with a diagram inlines the bundled mermaid under the page nonce`() {
        val html = page(diagram, diagram.replace("B", "C"))
        val nonce = Regex("script-src 'nonce-([0-9a-f]+)'").find(html)!!.groupValues[1]
        val scripts = Regex("<script nonce=\"([0-9a-f]+)\">").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(3, scripts.size)
        assertTrue(scripts.all { it == nonce })
        assertTrue(html.contains("globalThis[\"mermaid\"]"))
        assertTrue(html.contains("mdrDrawDiagrams("))
        // The CSP itself is unchanged: no remote or eval sources for the library.
        assertTrue(html.contains("default-src 'none'; img-src file: data:; style-src 'unsafe-inline'; script-src 'nonce-$nonce'\""))
        // Nothing in the inlined library closes its <script> early.
        assertEquals(3, Regex("</script>").findAll(html).count())
        assertTrue(html.endsWith("</body></html>"))
    }

    fun `test page without diagrams does not load mermaid`() {
        val html = page("# Title\n", "# Other title\n")
        assertFalse(html.contains("mdrDrawDiagrams("))
        assertEquals(1, Regex("<script nonce=").findAll(html).count())
    }

    private val formula = "Area: \$A = \\pi r^2\$\n\n\$\$\nE = mc^2\n\$\$\n"

    fun `test page with a formula inlines the bundled KaTeX under the page nonce (P2_8)`() {
        val html = page(formula, formula.replace("mc^2", "mc^3"))
        val nonce = Regex("script-src 'nonce-([0-9a-f]+)'").find(html)!!.groupValues[1]
        val scripts = Regex("<script nonce=\"([0-9a-f]+)\">").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(scripts.size > 1)
        assertTrue(scripts.all { it == nonce })
        assertTrue(html.contains("katex.render("))
        assertTrue(html.contains("mdrDrawMath("))
        // Offline: nothing in the page's policy reaches the network for the library or its fonts.
        val csp = Regex("Content-Security-Policy\" content=\"([^\"]+)\"").find(html)!!.groupValues[1]
        assertFalse(csp, csp.contains("http"))
        assertFalse(csp, csp.contains("unsafe-eval"))
        assertEquals(scripts.size, Regex("</script>").findAll(html).count())
        assertTrue(html.endsWith("</body></html>"))
    }

    fun `test page without formulas does not load KaTeX (P2_8)`() {
        for (text in listOf("# Title\n", "Plans cost \$5 a month, or \$10 with support.\n", "Code: `\$x\$`\n")) {
            val html = page(text, text.replace("Title", "Other title"))
            assertFalse(text, html.contains("mdrDrawMath("))
            assertFalse(text, html.contains("katex.render("))
        }
    }

    fun `test with math rendering off formulas stay source and KaTeX is not loaded (P2_8)`() {
        val diff = CoreMarkdownDiffRenderer().render(RenderRequest(formula, formula.replace("mc^2", "mc^3"), ViewMode.SIDE_BY_SIDE))
        val html = HtmlPage.build(diff, "void 0", 0, renderMath = false)
        assertTrue(html.contains("<div class=\"mdr-math mdr-math-display\">E = mc^3</div>"))
        assertFalse(html.contains("katex.render("))
        assertFalse(html.contains("font-src"))
    }

    fun `test page with diagrams and formulas loads both libraries (P2_8)`() {
        val html = page(diagram + "\n" + formula, diagram.replace("B", "C") + "\n" + formula)
        assertTrue(html.contains("mdrDrawDiagrams("))
        assertTrue(html.contains("mdrDrawMath("))
    }

    fun `test dark word marks are saturated and keep their hue`() {
        val inserted = java.awt.Color(0x294436)
        val word = HtmlPage.vivid(inserted)
        val hsb = java.awt.Color.RGBtoHSB(word.red, word.green, word.blue, null)
        val old = java.awt.Color.RGBtoHSB(inserted.red, inserted.green, inserted.blue, null)
        assertEquals(old[0], hsb[0], 0.01f)
        assertTrue(hsb[1] >= 0.59f && hsb[2] >= 0.41f)
        // A color that is already vivid enough stays as it is.
        val bright = java.awt.Color(0x2EA043)
        assertEquals(bright.rgb and 0xF0F0F0, HtmlPage.vivid(bright).rgb and 0xF0F0F0)
    }

    fun `test colors set in the color scheme replace the derived ones`() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val custom = mapOf(
            MarkdownReviewColors.ADDED_BLOCK to ("--md-added-bg" to "#123456"),
            MarkdownReviewColors.REMOVED_BLOCK to ("--md-removed-bg" to "#234567"),
            MarkdownReviewColors.MODIFIED_BLOCK to ("--md-modified-bg" to "#345678"),
            MarkdownReviewColors.ADDED_WORDS to ("--md-added-word-bg" to "#456789"),
            MarkdownReviewColors.REMOVED_WORDS to ("--md-removed-word-bg" to "#56789a"),
        )
        try {
            for ((key, value) in custom) {
                scheme.setAttributes(key, TextAttributes(null, Color.decode(value.second), null, null, Font.PLAIN))
            }
            val css = HtmlPage.themeCss()
            for ((name, color) in custom.values) assertTrue(css, css.contains("$name: $color;"))
        } finally {
            for (key in custom.keys) scheme.setAttributes(key, TextAttributes())
        }
        val css = HtmlPage.themeCss()
        for ((name, color) in custom.values) assertFalse(css, css.contains("$name: $color;"))
    }

    fun `test color settings page demo uses every color`() {
        val page = MarkdownReviewColorSettingsPage()
        val tags = page.additionalHighlightingTagToDescriptorMap
        assertEquals(page.attributeDescriptors.map { it.key }.toSet(), tags.values.toSet())
        for (tag in tags.keys) assertTrue(tag, page.demoText.contains("<$tag>"))
    }
}
