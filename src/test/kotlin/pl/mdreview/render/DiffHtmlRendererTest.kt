package pl.mdreview.render

import pl.mdreview.api.ViewMode
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContext
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.Container
import pl.mdreview.engine.MarkdownDiffEngine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffHtmlRendererTest {

    private val engine = MarkdownDiffEngine()

    private fun renderAdoc(left: List<Block>, right: List<Block>, mode: ViewMode = ViewMode.SIDE_BY_SIDE) =
        AsciiDocTestData.render(left, right, mode)

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

    @Test
    fun `merged view shows a removed formula as a formula, not as its source`() {
        val html = render("Speed: \$v = d / t\$ here.", "Speed: \$v = s / t\$ here.", RenderOptions(mode = ViewMode.MERGED))
        assertContains(
            html,
            "<del class=\"mdr-del\"><span class=\"mdr-math mdr-math-inline\">v = d / t</span></del>" +
                "<ins class=\"mdr-ins\"><span class=\"mdr-math mdr-math-inline\">v = s / t</span></ins>",
        )
    }

    @Test
    fun `a block formula shows its source until the page draws it`() {
        val html = render("\$\$\na^2 + b^2\n\$\$\n", "\$\$\na^2 + b^2 = c^2\n\$\$\n")
        assertContains(html, "<div class=\"mdr-row blk-modified mdr-math-row\" data-idx=\"0\" data-block-id=\"b0\">")
        assertContains(html, "<div class=\"mdr-block mdr-modified mdr-math-block\"><div class=\"mdr-math mdr-math-display\">a^2 + b^2 = c^2</div></div>")
        // The source diff behind the toggle is a word diff of the changed line.
        assertContains(html, "<ins class=\"mdr-ins\"> = c^2</ins>")
    }

    @Test
    fun `a block formula inside a list item is rendered and marked as a whole`() {
        val html = render("- item\n\n  \$\$\n  x^2\n  \$\$\n", "- item\n\n  \$\$\n  x^3\n  \$\$\n")
        assertContains(html, "<del class=\"mdr-del\"><div class=\"mdr-math mdr-math-display\">x^2</div></del>")
        assertContains(html, "<ins class=\"mdr-ins\"><div class=\"mdr-math mdr-math-display\">x^3</div></ins>")
    }

    // ---------- AsciiDoc blocks (P4.4) ----------

    @Test
    fun `asciidoc heading renders its level and word marks in both modes`() {
        val left = listOf(AsciiDocTestData.heading("Old title", level = 2, raw = "== Old title"))
        val right = listOf(AsciiDocTestData.heading("New title", level = 2, raw = "== New title"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "<div class=\"mdr-block mdr-modified\"><h2>", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">Old</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">New</ins> title</h2></div>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc heading level change gets the heading note in both modes`() {
        val left = listOf(AsciiDocTestData.heading("Title", level = 2, raw = "== Title"))
        val right = listOf(AsciiDocTestData.heading("Title", level = 3, raw = "=== Title"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "mdr-meta-changed\" data-change-note=\"h2 \u2192 h3\"", message = mode.name)
            assertEquals(1, Regex("data-change-note").findAll(html).count(), mode.name)
        }
    }

    @Test
    fun `asciidoc paragraph renders a p with word marks in both modes`() {
        val left = listOf(AsciiDocTestData.paragraph("The quick brown fox."))
        val right = listOf(AsciiDocTestData.paragraph("The quick red fox."))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "<p>The quick ", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">brown</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">red</ins> fox.</p>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc paragraph does not double a p that Asciidoctor already emitted`() {
        val left = listOf(AsciiDocTestData.paragraph("Same text.", html = "<p>Same <strong>text</strong>.</p>", raw = "Same *text*."))
        val right = listOf(AsciiDocTestData.paragraph("Same text.", html = "<p>Same <em>text</em>.</p>", raw = "Same _text_."))
        val html = renderAdoc(left, right)
        assertFalse(html.contains("<p><p>"))
        assertContains(html, "<div class=\"mdr-block mdr-modified mdr-meta-changed\" data-change-note=\"formatting changed\"><p>")
        assertEquals(2, Regex("<p>").findAll(html).count())
    }

    @Test
    fun `asciidoc list items render at their depth in both modes`() {
        val left = listOf(AsciiDocTestData.listItem("First item", depth = 2, raw = "** First item"))
        val right = listOf(AsciiDocTestData.listItem("Second item", depth = 2, raw = "** Second item"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "<ul class=\"mdr-li\" style=\"--depth:1\"><li class=\"mdr-block mdr-modified\">", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">First</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">Second</ins> item", message = mode.name)
        }
    }

    @Test
    fun `asciidoc ordered list keeps its number and renders as ol`() {
        val block = AsciiDocTestData.listItem("Third", ordered = true, number = 3, raw = "3. Third")
        for (mode in ViewMode.entries) {
            val html = renderAdoc(listOf(block), listOf(block), mode)
            assertContains(html, "<ol class=\"mdr-li\" style=\"--depth:0\" start=\"3\"><li class=\"mdr-block mdr-equal\">Third</li></ol>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc checklist renders the checkbox and the state note in both modes`() {
        val left = listOf(AsciiDocTestData.listItem("Ship it", raw = "* [ ] Ship it"))
        val right = listOf(AsciiDocTestData.listItem("Ship it", raw = "* [x] Ship it"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "mdr-task mdr-meta-changed\" data-change-note=\"unchecked \u2192 checked\"", message = mode.name)
            assertContains(html, "<input type=\"checkbox\" disabled=\"\" checked=\"\" /> Ship it", message = mode.name)
            assertFalse(html.contains("mdr-ins"), mode.name)
            assertFalse(html.contains("mdr-del"), mode.name)
        }
    }

    @Test
    fun `asciidoc callout list carries the callout class and start number`() {
        val block = AsciiDocTestData.listItem(
            "Install it",
            ordered = true,
            number = 2,
            containers = listOf(Container("callout")),
            raw = "<2> Install it",
        )
        assertContains(DiffHtmlRenderer().css(), "content: \"<\" counter(list-item)")
        for (mode in ViewMode.entries) {
            val html = renderAdoc(listOf(block), listOf(block), mode)
            assertContains(html, "<ol class=\"mdr-li mdr-callout\" style=\"--depth:0\" start=\"2\"><li class=\"mdr-block mdr-equal\">", message = mode.name)
            assertFalse(html.contains("mdr-container-callout"), mode.name)
        }
    }

    @Test
    fun `asciidoc description term and description render as a dl`() {
        val termOld = AsciiDocTestData.descriptionTerm("old.adoc", html = "<code>old.adoc</code>")
        val termNew = AsciiDocTestData.descriptionTerm("new.adoc", html = "<code>new.adoc</code>")
        val itemOld = AsciiDocTestData.descriptionItem("The old expected changes.")
        val itemNew = AsciiDocTestData.descriptionItem("The new expected changes.")
        for (mode in ViewMode.entries) {
            val html = renderAdoc(listOf(termOld, itemOld), listOf(termNew, itemNew), mode)
            assertContains(html, "<dl class=\"mdr-dlist\"><dt class=\"mdr-block mdr-modified\">", message = mode.name)
            assertContains(html, "<dl class=\"mdr-dlist\"><dd class=\"mdr-block mdr-modified\">", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">old</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">new</ins>", message = mode.name)
            assertFalse(html.contains("mdr-container-description"), mode.name)
        }
    }

    @Test
    fun `asciidoc table row renders cells and marks a changed cell`() {
        val left = listOf(AsciiDocTestData.tableRow("<td>1</td><td>2</td>", "1 | 2", "|1 |2"))
        val right = listOf(AsciiDocTestData.tableRow("<td>1</td><td>3</td>", "1 | 3", "|1 |3"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "<table class=\"mdr-trow\" style=\"--cols:2\"><tbody><tr class=\"mdr-block mdr-modified\">", message = mode.name)
            assertContains(html, "<td>1</td><td>", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">2</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">3</ins>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc header table row keeps th cells`() {
        val row = AsciiDocTestData.tableRow("<th>Name</th><th>Value</th>", "Name | Value", "|Name |Value", header = true)
        for (mode in ViewMode.entries) {
            val html = renderAdoc(listOf(row), listOf(row), mode)
            assertContains(html, "<th>Name</th><th>Value</th>", message = mode.name)
            assertContains(html, "class=\"mdr-block mdr-equal\"", message = mode.name)
        }
    }

    @Test
    fun `asciidoc code fence shows the language and a source language note`() {
        val left = listOf(AsciiDocTestData.codeFence("source", "java", "[source,java]"), AsciiDocTestData.codeLine("val x = 1", "java"))
        val right = listOf(AsciiDocTestData.codeFence("source", "kotlin", "[source,kotlin]"), AsciiDocTestData.codeLine("val y = 2", "kotlin"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "mdr-fence mdr-meta-changed\" data-change-note=\"java \u2192 kotlin\">kotlin</div>", message = mode.name)
            assertContains(html, "<span class=\"mdr-line mdr-modified\">val <del class=\"mdr-del\">x</del>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc code fence without a language still gets a language note`() {
        val left = listOf(AsciiDocTestData.codeFence("source", null, "[source]"))
        val right = listOf(AsciiDocTestData.codeFence("source", "kotlin", "[source,kotlin]"))
        val html = renderAdoc(left, right)
        assertContains(html, "data-change-note=\"source \u2192 kotlin\"")
    }

    @Test
    fun `asciidoc admonition paragraph is one alert block with a type title and marked body`() {
        val left = listOf(
            AsciiDocTestData.alert(
                "The body by default.",
                html = "The body by default.",
                container = false,
                alertContext = "NOTE",
                raw = "NOTE: The body by default.",
            ),
        )
        val right = listOf(
            AsciiDocTestData.alert(
                "The body unless enabled.",
                html = "The body unless enabled.",
                container = false,
                alertContext = "NOTE",
                raw = "NOTE: The body unless enabled.",
            ),
        )
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            val sides = if (mode == ViewMode.SIDE_BY_SIDE) 2 else 1
            assertEquals(sides, Regex("mdr-container-admonition").findAll(html).count(), mode.name)
            assertContains(html, "<p class=\"mdr-alert-title\">Note</p>", message = mode.name)
            assertContains(html, "<div class=\"mdr-alert-body\">The body ", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">by default</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">unless enabled</ins>", message = mode.name)
            assertContains(html, "mdr-container-first mdr-container-last", message = mode.name)
            assertFalse(html.contains("mdr-alert-title\" data-change-note"), mode.name)
            assertEquals(1, Regex("mdr-(?:row|item) blk-modified").findAll(html).count(), mode.name)
        }
    }

    @Test
    fun `asciidoc admonition type change gets the alert note and keeps one row`() {
        val left = listOf(AsciiDocTestData.alert("Careful.", type = "WARNING"))
        val right = listOf(AsciiDocTestData.alert("Careful.", type = "CAUTION"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "mdr-alert-caution", message = mode.name)
            assertContains(html, "data-change-note=\"Warning \u2192 Caution\"", message = mode.name)
            assertEquals(1, Regex("mdr-row blk-modified|mdr-item blk-modified").findAll(html).count(), mode.name)
        }
    }

    @Test
    fun `asciidoc delimited admonition frames its child rows and notes a type change`() {
        val warning = Container("admonition", type = "WARNING")
        val caution = Container("admonition", type = "CAUTION")
        val left = listOf(
            AsciiDocTestData.paragraph("Deleting a branch cannot be undone.", containers = listOf(warning)),
            AsciiDocTestData.paragraph("Check the target branch.", containers = listOf(warning)),
        )
        val right = listOf(
            AsciiDocTestData.paragraph("Deleting a branch cannot be undone.", containers = listOf(caution)),
            AsciiDocTestData.paragraph("Check the target branch.", containers = listOf(caution)),
        )
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            if (mode == ViewMode.SIDE_BY_SIDE) {
                assertContains(html, "mdr-container mdr-container-admonition mdr-alert mdr-alert-warning mdr-container-first", message = mode.name)
            }
            assertContains(html, "mdr-container mdr-container-admonition mdr-alert mdr-alert-caution mdr-container-first", message = mode.name)
            assertContains(html, "mdr-container-last", message = mode.name)
            assertContains(html, "data-change-note=\"Warning \u2192 Caution\"", message = mode.name)
        }
    }

    @Test
    fun `asciidoc block title is its own modified row with a word diff`() {
        val left = listOf(AsciiDocTestData.blockTitle("Original title", raw = ".Original title"))
        val right = listOf(AsciiDocTestData.blockTitle("New title", raw = ".New title"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "<div class=\"mdr-block mdr-modified mdr-block-title\">", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">Original</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">New</ins> title", message = mode.name)
        }
    }

    @Test
    fun `asciidoc block image renders a figure, resolves the target and marks a changed source`() {
        val left = listOf(AsciiDocTestData.image("Logo", "images/logo.png", title = "The logo"))
        val right = listOf(AsciiDocTestData.image("Logo", "../images/logo-dark.png", title = "The logo"))
        val options = RenderOptions(
            leftBaseUri = "file:///repo/docs/",
            rightBaseUri = "file:///repo/docs/",
            collapseUnchanged = false,
        )
        for (mode in ViewMode.entries) {
            val html = AsciiDocTestData.render(left, right, mode, options)
            if (mode == ViewMode.SIDE_BY_SIDE) {
                assertContains(html, "<figure class=\"mdr-block mdr-modified mdr-image\"><img src=\"file:///repo/docs/images/logo.png\" alt=\"Logo\" class=\"mdr-img-changed\" title=\"New URL: ../images/logo-dark.png\">", message = mode.name)
            }
            assertContains(html, "<img src=\"file:///repo/images/logo-dark.png\" alt=\"Logo\" class=\"mdr-img-changed\" title=\"Previous URL: images/logo.png\">", message = mode.name)
            assertContains(html, "<figcaption>The logo</figcaption>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc block image hides a remote source unless remote images are allowed`() {
        val left = listOf(AsciiDocTestData.image("Logo", "old.png"))
        val right = listOf(AsciiDocTestData.image("Logo", "https://example.com/logo.png"))
        assertFalse(renderAdoc(left, right).contains("src=\"https://example.com/logo.png\""))
        val allowed = AsciiDocTestData.render(left, right, options = RenderOptions(allowRemoteImages = true, collapseUnchanged = false))
        assertContains(allowed, "src=\"https://example.com/logo.png\"")
    }

    @Test
    fun `asciidoc attribute entry is one monospace line with word marks`() {
        val left = listOf(AsciiDocTestData.attribute(":product-version: 1.3"))
        val right = listOf(AsciiDocTestData.attribute(":product-version: 1.4"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "<div class=\"mdr-block mdr-modified mdr-attribute\">:product-version: 1.", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">3</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">4</ins>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc include renders a resolved link and word marks the target`() {
        val left = listOf(AsciiDocTestData.include("chapters/intro.adoc"))
        val right = listOf(AsciiDocTestData.include("chapters/introduction.adoc"))
        val options = RenderOptions(leftBaseUri = "file:///book/", rightBaseUri = "file:///book/", collapseUnchanged = false)
        for (mode in ViewMode.entries) {
            val html = AsciiDocTestData.render(left, right, mode, options)
            assertContains(html, "<div class=\"mdr-block mdr-modified mdr-include\">include::<a href=\"file:///book/chapters/introduction.adoc\" class=\"mdr-link-changed\" title=\"Previous URL: chapters/intro.adoc\">", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">intro</del>", message = mode.name)
            assertContains(html, "<ins class=\"mdr-ins\">introduction</ins>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc include target cannot inject a javascript link`() {
        val block = AsciiDocTestData.include("javascript:alert(1)")
        for (mode in ViewMode.entries) {
            val html = AsciiDocTestData.render(listOf(block), listOf(block), mode)
            assertFalse(html.contains("href=\"javascript:"), mode.name)
            assertContains(html, "javascript:alert(1)", message = mode.name)
        }
    }

    @Test
    fun `asciidoc comments are hidden when unchanged and visible when changed`() {
        val same = AsciiDocTestData.comment("keep this")
        for (mode in ViewMode.entries) {
            val hidden = renderAdoc(listOf(same), listOf(same), mode)
            assertTrue(Regex("blk-equal[^>]*hidden").containsMatchIn(hidden), mode.name)
        }
        val left = listOf(AsciiDocTestData.comment("old note"))
        val right = listOf(AsciiDocTestData.comment("new note"))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "mdr-comment\" title=\"HTML comment\"", message = mode.name)
            assertContains(html, "<del class=\"mdr-del\">old</del>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc thematic break and page break render as hr with and without the page class`() {
        val thematic = AsciiDocTestData.thematicBreak("'''")
        val page = AsciiDocTestData.thematicBreak("<<<")
        for (mode in ViewMode.entries) {
            val html = renderAdoc(listOf(thematic, page), listOf(thematic, page), mode)
            assertContains(html, "<div class=\"mdr-block mdr-equal\"><hr /></div>", message = mode.name)
            assertContains(html, "<div class=\"mdr-block mdr-equal\"><hr class=\"mdr-page-break\" /></div>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc inline markup keeps kbd, menu classes, mark and footnote anchors`() {
        val html = "Press <kbd>Ctrl</kbd>+<kbd>K</kbd>, <span class=\"menuseq\"><b>File</b> &gt; <b>Open</b></span>, " +
            "<b class=\"menuref\">Open</b>, <mark>important</mark>, note<sup class=\"footnote\"><a href=\"#_footnote_1\">1</a></sup>."
        val plain = "Press Ctrl+K, File > Open, Open, important, note1."
        val block = AsciiDocTestData.paragraph(plain, html)
        for (mode in ViewMode.entries) {
            val rendered = renderAdoc(listOf(block), listOf(block), mode)
            assertContains(rendered, "<kbd>Ctrl</kbd>", message = mode.name)
            assertContains(rendered, "<span class=\"menuseq\"><b>File</b> &gt; <b>Open</b></span>", message = mode.name)
            assertContains(rendered, "<b class=\"menuref\">Open</b>", message = mode.name)
            assertContains(rendered, "<mark>important</mark>", message = mode.name)
            assertContains(rendered, "<sup class=\"footnote\"><a href=\"#mdr-fn-right-1\">1</a></sup>", message = mode.name)
        }
    }

    @Test
    fun `asciidoc sanitizer keeps only safe inline classes and no script`() {
        val clean = pl.mdreview.engine.HtmlFragment.parse(
            "<p class=\"x menuseq\" onclick=\"x()\"><mark>hi</mark><script>alert(1)</script>" +
                "<span class=\"evil\">there</span><a href=\"javascript:alert(2)\">bad</a>" +
                "<img src=\"x.png\" onerror=\"y()\"></p>",
        ).joinToString("") { part ->
            when (part) {
                is pl.mdreview.engine.HtmlFragment.Markup -> part.html
                is pl.mdreview.engine.HtmlFragment.TextRun -> part.text
            }
        }
        assertContains(clean, "<p class=\"menuseq\">")
        assertFalse(clean.contains("onclick"))
        assertFalse(clean.contains("<script"))
        assertFalse(clean.contains("evil"))
        assertFalse(clean.contains("javascript:"))
        assertFalse(clean.contains("onerror"))
        assertContains(clean, "<mark>hi</mark>")
        assertContains(clean, "<img src=\"x.png\">")
    }

    @Test
    fun `asciidoc link, xref and anchor target changes keep the text and mark the link`() {
        val left = listOf(AsciiDocTestData.paragraph(
            "See docs and settings.",
            "See <a href=\"https://example.com/old\">docs</a> and <a href=\"#settings\">settings</a>.",
        ))
        val right = listOf(AsciiDocTestData.paragraph(
            "See docs and settings.",
            "See <a href=\"https://example.com/new\">docs</a> and <a href=\"#configuration\">settings</a>.",
        ))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "class=\"mdr-link-changed\"", message = mode.name)
            assertEquals(if (mode == ViewMode.SIDE_BY_SIDE) 4 else 2, Regex("mdr-link-changed").findAll(html).count(), mode.name)
            assertContains(html, "Previous URL: https://example.com/old", message = mode.name)
            if (mode == ViewMode.SIDE_BY_SIDE) assertContains(html, "New URL: https://example.com/new", message = mode.name)
            assertContains(html, "Previous URL: #settings", message = mode.name)
            assertFalse(html.contains("<ins class=\"mdr-ins\""), mode.name)
        }
    }

    @Test
    fun `asciidoc markup-only inline change gets the formatting changed note`() {
        val left = listOf(AsciiDocTestData.paragraph("A word.", html = "A <strong>word</strong>.", raw = "A *word*."))
        val right = listOf(AsciiDocTestData.paragraph("A word.", html = "A <em>word</em>.", raw = "A _word_."))
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, right, mode)
            assertContains(html, "mdr-meta-changed\" data-change-note=\"formatting changed\"", message = mode.name)
            assertFalse(html.contains("mdr-ins"), mode.name)
            assertFalse(html.contains("mdr-del"), mode.name)
        }
    }

    @Test
    fun `asciidoc containers frame each row and mark first and last on both modes`() {
        val example = Container("example", title = "Example block")
        val blocks = (1..3).map { AsciiDocTestData.paragraph("Paragraph $it.", containers = listOf(example), line = it) }
        for (mode in ViewMode.entries) {
            val html = renderAdoc(blocks, blocks, mode)
            val containers = Regex("<div class=\"mdr-container mdr-container-example[^\"]*\">").findAll(html).map { it.value }.toList()
            // Three rows on each side in the side-by-side view, three items in the merged view.
            assertEquals(if (mode == ViewMode.SIDE_BY_SIDE) 6 else 3, containers.size, mode.name)
            assertEquals(if (mode == ViewMode.SIDE_BY_SIDE) 2 else 1, containers.count { "mdr-container-first mdr-container-last" !in it && "mdr-container-first" in it }, mode.name)
            assertEquals(if (mode == ViewMode.SIDE_BY_SIDE) 2 else 1, containers.count { "mdr-container-last" in it && "mdr-container-first" !in it }, mode.name)
            assertContains(html, "<div class=\"mdr-block-title\">Example block</div>", message = mode.name)
        }
    }

    @Test
    fun `every framing container kind gets its class in both modes`() {
        for (kind in listOf("example", "sidebar", "quote", "verse", "open")) {
            val container = Container(kind)
            val block = AsciiDocTestData.paragraph("Inside.", containers = listOf(container))
            for (mode in ViewMode.entries) {
                val html = renderAdoc(listOf(block), listOf(block), mode)
                assertContains(html, "mdr-container mdr-container-$kind mdr-container-first mdr-container-last", message = "$kind ${mode.name}")
            }
        }
    }

    @Test
    fun `asciidoc nested containers nest outermost first and keep their own first and last`() {
        val outer = Container("example", title = "Outer")
        val inner = Container("sidebar", title = "Inner")
        val blocks = listOf(
            AsciiDocTestData.paragraph("Outer content.", containers = listOf(outer)),
            AsciiDocTestData.paragraph("Inner content.", containers = listOf(outer, inner)),
        )
        for (mode in ViewMode.entries) {
            val html = renderAdoc(blocks, blocks, mode)
            assertContains(html, "<div class=\"mdr-container mdr-container-example mdr-container-first\"><div class=\"mdr-block-title\">Outer</div>", message = mode.name)
            assertContains(
                html,
                "<div class=\"mdr-container mdr-container-example mdr-container-last\">" +
                    "<div class=\"mdr-container mdr-container-sidebar mdr-container-first mdr-container-last\">" +
                    "<div class=\"mdr-block-title\">Inner</div><div class=\"mdr-block mdr-equal\"><p>Inner content.</p>",
                message = mode.name,
            )
        }
    }

    @Test
    fun `a container on one side never frames the other side's placeholder`() {
        val example = Container("example")
        val left = listOf(AsciiDocTestData.paragraph("Kept.", line = 0))
        val right = listOf(
            AsciiDocTestData.paragraph("Kept.", line = 0),
            AsciiDocTestData.paragraph("Added inside.", containers = listOf(example), line = 2),
        )
        val html = renderAdoc(left, right)
        assertContains(html, "<div class=\"mdr-side mdr-left\" data-side=\"left\"><div class=\"mdr-placeholder\"></div></div>")
        assertContains(html, "<div class=\"mdr-side mdr-right\" data-side=\"right\" data-line=\"2\"><div class=\"mdr-container mdr-container-example mdr-container-first mdr-container-last\">")
    }

    @Test
    fun `merged view frames removed rows from the left side and added rows from the right`() {
        val example = Container("quote", title = "Quoted")
        val removed = AsciiDocTestData.paragraph("Gone.", containers = listOf(example), line = 0)
        val added = AsciiDocTestData.paragraph("New.", containers = listOf(example), line = 2)
        val html = renderAdoc(listOf(removed), listOf(added), ViewMode.MERGED)
        assertContains(html, "<div class=\"mdr-item blk-removed\"")
        assertContains(html, "<div class=\"mdr-container mdr-container-quote mdr-container-first\">")
        assertContains(html, "<div class=\"mdr-block-title\">Quoted</div>")
        assertContains(html, "<div class=\"mdr-container mdr-container-quote mdr-container-last\">")
        assertContains(html, "<div class=\"mdr-block mdr-added\"><p>New.</p>")
    }

    @Test
    fun `a block title row inside a container is not duplicated by the container title`() {
        val example = Container("example", title = "Title")
        val left = listOf(
            AsciiDocTestData.blockTitle("Title", html = "Title", raw = ".Title").let {
                AsciiDocTestData.block(BlockKind.BLOCK_TITLE, it.plainText, "Title", ".Title", BlockContext(containers = listOf(example)))
            },
            AsciiDocTestData.paragraph("Content.", containers = listOf(example)),
        )
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, left, mode)
            assertEquals(if (mode == ViewMode.SIDE_BY_SIDE) 2 else 1, Regex("mdr-block-title").findAll(html).count(), mode.name)
        }
    }

    @Test
    fun `a block title row before a container is not duplicated by the container title`() {
        val example = Container("example", title = "Title")
        val left = listOf(
            AsciiDocTestData.blockTitle("Title", html = "Title", raw = ".Title"),
            AsciiDocTestData.paragraph("Content.", containers = listOf(example)),
        )
        for (mode in ViewMode.entries) {
            val html = renderAdoc(left, left, mode)
            assertEquals(if (mode == ViewMode.SIDE_BY_SIDE) 2 else 1, Regex("mdr-block-title").findAll(html).count(), mode.name)
            assertFalse(html.contains("<div class=\"mdr-container mdr-container-example mdr-container-first\"><div class=\"mdr-block-title\">"), mode.name)
        }
    }
}

