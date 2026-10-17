package pl.mdreview.render

import pl.mdreview.api.ViewMode
import pl.mdreview.engine.MarkdownDiffEngine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffHtmlRendererTest {

    private val engine = MarkdownDiffEngine()

    private fun render(left: String, right: String, options: RenderOptions = RenderOptions()) =
        DiffHtmlRenderer(options).renderBody(engine.diff(left, right))

    private fun rows(html: String) = Regex("<div class=\"mdr-row ").findAll(html).count()

    @Test
    fun `side by side has one row per block and a placeholder for added blocks`() {
        val html = render("# A\n\ntext\n", "# A\n\nnew\n\ntext\n")
        assertEquals(3, rows(html))
        assertContains(html, "<div class=\"mdr-row blk-added\" data-idx=\"1\" data-block-id=\"b1\">")
        assertContains(html, "<div class=\"mdr-side mdr-left\" data-side=\"left\"><div class=\"mdr-placeholder\"></div></div>")
        assertContains(html, "<div class=\"mdr-side mdr-right\" data-side=\"right\" data-line=\"2\"><div class=\"mdr-block mdr-added\">")
    }

    @Test
    fun `modified words are marked on each side`() {
        val html = render("The quick brown fox.", "The quick red fox.")
        assertContains(html, "<p>The quick <del class=\"mdr-del\">brown</del> fox.</p>")
        assertContains(html, "<p>The quick <ins class=\"mdr-ins\">red</ins> fox.</p>")
    }

    @Test
    fun `merged view shows removed words inline`() {
        val html = render("The quick brown fox.", "The quick red fox.", RenderOptions(mode = ViewMode.MERGED))
        assertContains(html, "<div class=\"mdr-root mdr-merged\">")
        assertContains(html, "<p>The quick <del class=\"mdr-del\">brown</del><ins class=\"mdr-ins\">red</ins> fox.</p>")
        assertContains(html, "<div class=\"mdr-item blk-modified\" data-idx=\"0\" data-block-id=\"b0\" data-side=\"right\" data-line=\"0\">")
        assertFalse(html.contains("mdr-side"))
    }

    @Test
    fun `merged view shows removed blocks from the left side`() {
        val html = render("a\n\nb\n", "a\n", RenderOptions(mode = ViewMode.MERGED))
        assertContains(html, "<div class=\"mdr-item blk-removed\" data-idx=\"1\" data-block-id=\"b1\" data-side=\"left\" data-line=\"2\"><div class=\"mdr-block mdr-removed\"><p>b</p>")
    }

    @Test
    fun `marks survive inline formatting`() {
        val html = render("Use **bold** text here.", "Use **bold** words here.")
        assertContains(html, "<strong>bold</strong> <del class=\"mdr-del\">text</del> here.")
        assertContains(html, "<strong>bold</strong> <ins class=\"mdr-ins\">words</ins> here.")
    }

    @Test
    fun `strikethrough in content stays a plain del element`() {
        val html = render("~~old~~ value", "~~old~~ value")
        assertContains(html, "<del>old</del>")
    }

    @Test
    fun `table rows render as aligned single-row tables`() {
        val html = render("| a | b |\n| - | - |\n| 1 | 2 |\n", "| a | b |\n| - | - |\n| 1 | 3 |\n")
        assertContains(html, "<table class=\"mdr-trow\" style=\"--cols:2\"><tbody><tr class=\"mdr-block mdr-equal\">")
        assertContains(html, "<th>a</th>")
        assertContains(html, "<tr class=\"mdr-block mdr-modified\">")
        assertContains(html, "<td><ins class=\"mdr-ins\">3</ins></td>")
    }

    @Test
    fun `code lines are escaped and marked`() {
        val html = render("```\nif (a < b) x()\n```\n", "```\nif (a < c) x()\n```\n")
        assertContains(html, "<span class=\"mdr-line mdr-modified\">if (a &lt; <ins class=\"mdr-ins\">c</ins>) x()</span>")
        assertContains(html, "<div class=\"mdr-block mdr-equal mdr-fence\">code</div>")
    }

    @Test
    fun `front matter gets a labelled fence`() {
        val html = render("---\na: 1\n---\n# T\n", "---\na: 2\n---\n# T\n")
        assertContains(html, "mdr-fence\">front matter</div>")
        assertContains(html, "mdr-codeline mdr-fm-line")
    }

    @Test
    fun `long unchanged runs fold into details`() {
        val left = (1..20).joinToString("\n\n") { "p$it" }
        val right = left.replace("p20", "changed")
        val html = render(left, right)
        assertContains(html, "<details class=\"mdr-fold\"><summary>17 unchanged blocks</summary>")
        val unfolded = render(left, right, RenderOptions(collapseUnchanged = false))
        assertFalse(unfolded.contains("<details"))
    }

    @Test
    fun `identical documents show a notice and are not folded`() {
        val md = (1..20).joinToString("\n\n") { "p$it" }
        val html = render(md, md)
        assertContains(html, "<div class=\"mdr-empty\">No differences</div>")
        assertFalse(html.contains("<details"))
    }

    @Test
    fun `body has no scripts or handlers and raw html is sanitized`() {
        val html = render("", "<div onclick=\"x()\">hi<script>alert(1)</script></div>\n")
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("alert(1)"))
        assertFalse(html.contains("onclick"))
    }

    @Test
    fun `nested and task list items`() {
        val html = render("- a\n    - [x] b\n", "- a\n    - [x] b\n")
        assertContains(html, "<ul class=\"mdr-li\" style=\"--depth:1\"><li class=\"mdr-block mdr-equal mdr-task\">")
        assertContains(html, "type=\"checkbox\"")
    }

    @Test
    fun `ordered list keeps its number`() {
        val html = render("3. c\n", "3. c\n")
        assertContains(html, "<ol class=\"mdr-li\" style=\"--depth:0\" start=\"3\">")
    }

    @Test
    fun `quote depth wraps the block`() {
        val html = render("> quoted\n", "> quoted text\n")
        assertTrue(html.contains("<blockquote><div class=\"mdr-block mdr-modified\"><p>quoted</p>"))
    }

    @Test
    fun `css bundles diff css and the variable bridge`() {
        val css = DiffHtmlRenderer().css()
        assertContains(css, ".mdr-block.mdr-added")
        assertContains(css, "--mdr-bg: var(--md-bg);")
        assertFalse(css.contains("</style", ignoreCase = true))
    }

    @Test
    fun `checkbox-only change carries a note on the new side`() {
        val html = render("- [ ] ship it\n", "- [x] ship it\n")
        assertContains(html, "<li class=\"mdr-block mdr-modified mdr-task mdr-meta-changed\" data-change-note=\"unchecked → checked\">")
        assertEquals(1, Regex("mdr-meta-changed").findAll(html).count())
        val merged = render("- [ ] ship it\n", "- [x] ship it\n", RenderOptions(mode = ViewMode.MERGED))
        assertContains(merged, "data-change-note=\"unchecked → checked\"")
    }

    @Test
    fun `content strikethrough next to a word change`() {
        val html = render("costs 10 EUR", "costs ~~10 EUR~~ 12 EUR")
        assertContains(html, "<del>10 EUR</del><ins class=\"mdr-ins\"> 12 EUR</ins>")
        assertContains(DiffHtmlRenderer().css(), "del:not(.mdr-del)")
    }

    @Test
    fun `bare urls become links`() {
        val html = render("See https://example.com/a here", "See https://example.com/b here")
        assertContains(html, "<a rel=\"nofollow\" href=\"https://example.com/a\">https://example.com/<del class=\"mdr-del\">a</del></a>")
        assertContains(html, "<a rel=\"nofollow\" href=\"https://example.com/b\">https://example.com/<ins class=\"mdr-ins\">b</ins></a>")
    }

    @Test
    fun `footnote references link to the definition on the same side`() {
        val html = render("Text[^n].\n\n[^n]: Old note.\n", "Text[^n].\n\n[^n]: New note.\n")
        assertContains(html, "<sup class=\"mdr-fnref\"><a href=\"#mdr-fn-left-n\">n</a></sup>")
        assertContains(html, "<sup class=\"mdr-fnref\"><a href=\"#mdr-fn-right-n\">n</a></sup>")
        assertContains(
            html,
            "<div class=\"mdr-block mdr-modified mdr-footnote\" id=\"mdr-fn-right-n\"><span class=\"mdr-fn-label\">n</span>" +
                "<div class=\"mdr-fn-body\"><p><ins class=\"mdr-ins\">New</ins> note.</p>",
        )
        // The definition is not repeated at the end of the block that references it.
        assertFalse(html.contains("class=\"footnotes\""))
    }

    @Test
    fun `changed footnote reference is marked`() {
        val html = render("A[^a].\n\n[^a]: x\n\n[^b]: y\n", "A[^b].\n\n[^a]: x\n\n[^b]: y\n")
        assertContains(html, "<a href=\"#mdr-fn-left-a\"><del class=\"mdr-del\">a</del></a>")
        assertContains(html, "<a href=\"#mdr-fn-right-b\"><ins class=\"mdr-ins\">b</ins></a>")
    }

    @Test
    fun `alerts render with their type and a note when the type changes`() {
        val html = render("> [!WARNING]\n> Careful.\n", "> [!CAUTION]\n> Careful.\n")
        assertContains(html, "<blockquote class=\"mdr-alert mdr-alert-warning\"><p class=\"mdr-block mdr-modified mdr-alert-title\"><del class=\"mdr-del\">Warning</del></p>")
        assertContains(
            html,
            "<blockquote class=\"mdr-alert mdr-alert-caution\"><p class=\"mdr-block mdr-modified mdr-alert-title mdr-meta-changed\" " +
                "data-change-note=\"Warning → Caution\"><ins class=\"mdr-ins\">Caution</ins></p>",
        )
        assertContains(html, "<blockquote class=\"mdr-alert mdr-alert-caution\"><div class=\"mdr-block mdr-equal\"><p>Careful.</p>")
        assertContains(DiffHtmlRenderer().css(), "--mdr-alert-caution")
    }

    @Test
    fun `quotes nested in an alert keep the alert on the outermost quote`() {
        val html = render("> [!NOTE]\n> > inner\n", "> [!NOTE]\n> > inner\n")
        assertContains(html, "<blockquote class=\"mdr-alert mdr-alert-note\"><blockquote><div class=\"mdr-block mdr-equal\"><p>inner</p>")
    }
}
