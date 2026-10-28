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

    @Test
    fun `relative images resolve against each side's base uri`() {
        val options = RenderOptions(leftBaseUri = "file:///repo/v1/docs/", rightBaseUri = "file:///repo/v2/docs/")
        val html = render("![a](img/a.png)\n", "![a](../a.png)\n", options)
        assertContains(html, "<img src=\"file:///repo/v1/docs/img/a.png\" alt=\"a\"")
        assertContains(html, "<img src=\"file:///repo/v2/a.png\" alt=\"a\"")
        // Without a base URI the path stays as written.
        assertContains(render("![a](img/a.png)\n", "![a](img/a.png)\n"), "<img src=\"img/a.png\"")
    }

    @Test
    fun `only relative paths are resolved`() {
        val base = "file:///repo/docs/"
        assertEquals("file:///repo/docs/a%20b.png", LinkAttributeProvider.resolve(base, "a%20b.png"))
        assertEquals("file:///repo/docs/sub/x.svg?raw=1", LinkAttributeProvider.resolve(base, "sub/x.svg?raw=1"))
        assertEquals(null, LinkAttributeProvider.resolve(base, "https://example.com/x.png"))
        assertEquals(null, LinkAttributeProvider.resolve(base, "data:image/png;base64,AAAA"))
        assertEquals(null, LinkAttributeProvider.resolve(base, "/root.png"))
        assertEquals(null, LinkAttributeProvider.resolve(base, "#frag"))
        assertEquals(null, LinkAttributeProvider.resolve(base, ""))
    }

    @Test
    fun `heading level change gets a note, a text change does not`() {
        val html = render("### Title\n\n## Other\n", "#### Title\n\n## Another\n")
        assertContains(html, "<div class=\"mdr-block mdr-modified mdr-meta-changed\" data-change-note=\"h3 \u2192 h4\"><h4>Title</h4>")
        // Only on the new side, like the other change notes.
        assertEquals(1, Regex("data-change-note").findAll(html).count())
    }

    @Test
    fun `link marks pair by text and occurrence`() {
        val html = render("[a](u1) [a](u2) [b](u3)\n", "[a](u1) [a](u9) [b](u3)\n")
        assertEquals(2, Regex("mdr-link-changed").findAll(html).count())
        assertContains(html, "href=\"u9\" class=\"mdr-link-changed\" title=\"Previous URL: u2\"")
    }

    @Test
    fun `link changes in list items are marked`() {
        val html = render("- [x][r]\n\n[r]: https://a.example\n", "- [x][r]\n\n[r]: https://b.example\n")
        assertContains(html, "<li class=\"mdr-block mdr-modified\">")
        assertContains(html, "title=\"Previous URL: https://a.example\"")
    }

    @Test
    fun `sanitizer keeps an allowlist of tags, attributes and url schemes`() {
        val clean = HtmlSanitizer.sanitize(
            "<p align=\"center\" class=\"x\" style=\"color:red\" onclick=\"a()\">Hi <b>there</b></p>" +
                "<a href=\" JaVaScRiPt:alert(1)\">a</a><a href=\"vbscript:x\">b</a><a href=\"#top\">c</a>" +
                "<img src=\"data:image/png;base64,AA\"><img src=\"data:text/html,x\"><img src=\"https://e.com/a.png\">" +
                "<svg><script>x()</script><circle onload=\"y()\"/></svg><form action=\"/x\"><input></form>1 < 2",
        )
        assertEquals(
            "<p align=\"center\">Hi <b>there</b></p><a>a</a><a>b</a><a href=\"#top\">c</a>" +
                "<img src=\"data:image/png;base64,AA\"><img><img src=\"\">1 &lt; 2",
            clean,
        )
        assertContains(HtmlSanitizer.sanitize("<img src=\"https://e.com/a.png\">", allowRemoteImages = true), "src=\"https://e.com/a.png\"")
    }

    @Test
    fun `inline html inside markdown is sanitized too`() {
        val html = render("", "Text <span onclick=\"x()\">here</span> and <a href=\"javascript:y()\">link</a>.\n\n- <img src=x onerror=z()>\n")
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("javascript:"))
        assertFalse(html.contains("onerror"))
        assertContains(html, "<span>here</span>")
    }

    @Test
    fun `details group folds its unchanged rows and stays closed without changes`() {
        val inner = (1..8).joinToString("\n\n") { "Paragraph $it." }
        val md = "<details>\n<summary>More</summary>\n\n$inner\n\n</details>\n\nAfter.\n"
        val html = render(md, md.replace("After.", "After, changed."))
        assertContains(html, "<details class=\"mdr-details\"><summary class=\"mdr-details-summary\"><div class=\"mdr-row blk-equal\" data-idx=\"0\"")
        assertContains(html, "<span class=\"mdr-details-marker\"></span> More</div>")
        // The unchanged rows inside the group fold on their own, keeping context rows at both ends of the group.
        assertContains(html, "<details class=\"mdr-fold\"><summary>4 unchanged blocks</summary>")
        val changed = render(md, md.replace("Paragraph 4.", "Paragraph four."))
        assertContains(changed, "<details class=\"mdr-details\" open>")
    }

    @Test
    fun `unchanged html comments are hidden rows`() {
        val html = render("<!-- note -->\n\ntext\n", "<!-- note -->\n\ntext!\n")
        assertContains(html, "<div class=\"mdr-row blk-equal\" data-idx=\"0\" data-block-id=\"b0\" hidden>")
        val merged = render("<!-- note -->\n", "<!-- note -->\n\nx\n", RenderOptions(mode = ViewMode.MERGED))
        assertContains(merged, "data-block-id=\"b0\" hidden data-side=\"right\"")
    }

    @Test
    fun `added hard break shows a break marker`() {
        val html = render("one\ntwo\n", "one\\\ntwo\n")
        assertContains(html, "one<ins class=\"mdr-ins mdr-ws mdr-ws-break\"></ins><br />")
    }

    @Test
    fun `a changed inline formula is replaced as a whole, never word by word (P2_4)`() {
        val html = render("Speed: \$v = d / t\$ here.", "Speed: \$v = s / t\$ here.")
        assertContains(html, "<del class=\"mdr-del\"><span class=\"mdr-math mdr-math-inline\">v = d / t</span></del>")
        assertContains(html, "<ins class=\"mdr-ins\"><span class=\"mdr-math mdr-math-inline\">v = s / t</span></ins>")
    }

    @Test
    fun `formula source is escaped (P2_4)`() {
        val html = render("\$a < b\$ and\n\n\$\$\nx <script> y\n\$\$\n", "\$a < c\$ and\n\n\$\$\nx <script> z\n\$\$\n")
        assertContains(html, "a &lt; c")
        assertContains(html, "x &lt;script&gt; z")
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `dollar signs that do not form a formula stay text (P2_4)`() {
        for (text in listOf("Costs \$5 or \$10.", "A lone \$ sign.", "Spaced \$ x \$ is not a formula.", "Escaped \\\$x\\\$ too.")) {
            val html = render(text, "$text More.")
            assertFalse(html.contains("mdr-math"), text)
        }
    }
}

