package pl.mdreview.engine

import pl.mdreview.api.LineRange
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.DiffOptions as ApiDiffOptions
import pl.mdreview.api.ViewMode
import pl.mdreview.render.CoreMarkdownDiffRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs the shared fixtures in `src/test/resources/fixtures` (see its README.md). Each test pins the
 * block-level result described in that fixture's EXPECTED.md, including the choices EXPECTED.md
 * leaves to the engine.
 */
class FixturesTest {

    private val engine = MarkdownDiffEngine()

    private fun read(case: String, name: String): String {
        val url = javaClass.getResource("/fixtures/$case/$name") ?: error("Missing fixture $case/$name")
        return File(url.toURI()).readBytes().toString(Charsets.UTF_8)
    }

    private fun diff(case: String) = engine.diff(read(case, "old.md"), read(case, "new.md"))

    private fun html(case: String, mode: ViewMode = ViewMode.SIDE_BY_SIDE): String = CoreMarkdownDiffRenderer().render(
        RenderRequest(read(case, "old.md"), read(case, "new.md"), mode, ApiDiffOptions(collapseUnchanged = false)),
    ).bodyHtml

    /** One line per changed block: status, kind and the first words of its text. */
    private fun changes(case: String): List<String> = diff(case).changes.map {
        val b = (it.right ?: it.left)!!
        "${it.status} ${b.kind} ${b.plainText.trim().take(24).trim()}".trim()
    }

    @Test
    fun `01 paragraph edit`() {
        val d = diff("01-paragraph-edit")
        assertEquals(listOf(DiffStatus.EQUAL, DiffStatus.MODIFIED, DiffStatus.MODIFIED, DiffStatus.EQUAL), d.blocks.map { it.status })
        val inline = d.blocks[1].inline.filter { it.status == DiffStatus.ADDED }.map { it.text.trim() }
        assertTrue("directly" in inline)
    }

    @Test
    fun `02 block added`() = assertEquals(
        listOf("ADDED HEADING 1.1.0", "ADDED LIST_ITEM Merged view with striket", "ADDED LIST_ITEM Per-block revert."),
        changes("02-block-added"),
    )

    @Test
    fun `03 block removed`() = assertEquals(
        listOf(
            "REMOVED HEADING Experimental options",
            "REMOVED PARAGRAPH These options may disapp",
            "REMOVED LIST_ITEM Mermaid diagram renderin",
            "REMOVED LIST_ITEM Syntax highlighting in c",
        ),
        changes("03-block-removed"),
    )

    @Test
    fun `04 moved section is removed and added, never modified`() {
        val statuses = diff("04-block-moved").changes.map { it.status }
        assertEquals(listOf(DiffStatus.ADDED, DiffStatus.ADDED, DiffStatus.REMOVED, DiffStatus.REMOVED), statuses)
    }

    @Test
    fun `04 moved section is labeled as moved at both ends (P2_7)`() {
        val rendered = CoreMarkdownDiffRenderer().render(
            RenderRequest(read("04-block-moved", "old.md"), read("04-block-moved", "new.md"), ViewMode.SIDE_BY_SIDE, ApiDiffOptions(collapseUnchanged = false)),
        )
        // Statuses stay ADDED and REMOVED, so the IDE side and block actions keep working; the pairing is extra.
        val added = rendered.changes.filter { it.status == pl.mdreview.api.BlockStatus.ADDED }.map { it.idx }
        val removed = rendered.changes.filter { it.status == pl.mdreview.api.BlockStatus.REMOVED }.map { it.idx }
        assertEquals(2, added.size)
        assertEquals(2, removed.size)
        for (mode in ViewMode.entries) {
            val html = if (mode == ViewMode.SIDE_BY_SIDE) rendered.bodyHtml else html("04-block-moved", mode)
            // The heading and the paragraph each point at their other end: added at the top, removed at the end.
            for ((to, from) in added.zip(removed)) {
                assertContains(html, "data-idx=\"$to\"", message = mode.name)
                assertTrue(Regex("data-idx=\"$to\"[^>]*data-moved-from=\"$from\"").containsMatchIn(html), "$mode: block $to moved from $from")
                assertTrue(Regex("data-idx=\"$from\"[^>]*data-moved-to=\"$to\"").containsMatchIn(html), "$mode: block $from moved to $to")
            }
            assertEquals(4, Regex("class=\"mdr-moved-link\"").findAll(html).count(), mode.name)
            // A move has no word diff.
            assertFalse(html.contains("mdr-ins"), mode.name)
            assertFalse(html.contains("mdr-del"), mode.name)
        }
    }

    @Test
    fun `04 an edited or duplicated block is not a move (P2_7)`() {
        // The moved paragraph was also edited: keys differ, so it stays removed plus added.
        val edited = read("04-block-moved", "new.md").replace("All JetBrains IDEs", "Every JetBrains IDE")
        val html = CoreMarkdownDiffRenderer().render(
            RenderRequest(read("04-block-moved", "old.md"), edited, ViewMode.SIDE_BY_SIDE, ApiDiffOptions(collapseUnchanged = false)),
        ).bodyHtml
        assertEquals(1, Regex("data-moved-from=").findAll(html).count())
        assertEquals(1, Regex("data-moved-to=").findAll(html).count())
        // A copy added while the original stays in place is an addition, not a move.
        val copy = CoreMarkdownDiffRenderer().render(RenderRequest("A one\n\nB two\n", "A one\n\nB two\n\nA one\n", ViewMode.SIDE_BY_SIDE)).bodyHtml
        assertFalse(copy.contains("data-moved-"))
    }

    @Test
    fun `05 headings`() {
        assertEquals(
            listOf(
                "MODIFIED HEADING Capabilities",
                // Level change only: modified without word changes.
                "MODIFIED HEADING Side-by-side view",
                "MODIFIED HEADING Unified view",
            ),
            changes("05-headings"),
        )
        val levelOnly = diff("05-headings").changes[1]
        assertTrue(levelOnly.inline.all { it.status == DiffStatus.EQUAL })
        // The level change is spelled out under the new heading, like other notes, in both views.
        val note = "mdr-meta-changed\" data-change-note=\"h3 \u2192 h4\""
        assertEquals(1, html("05-headings").windowed(note.length).count { it == note })
        assertEquals(1, html("05-headings", ViewMode.MERGED).windowed(note.length).count { it == note })
        // Headings whose level did not change get no note.
        assertEquals(1, Regex("data-change-note").findAll(html("05-headings", ViewMode.MERGED)).count())
    }

    @Test
    fun `06 nested lists`() {
        assertEquals(
            listOf(
                "MODIFIED LIST_ITEM words and characters",
                "REMOVED LIST_ITEM HTML generator",
                "ADDED LIST_ITEM moves",
                "ADDED LIST_ITEM HTML generator",
                "MODIFIED LIST_ITEM Second, revised step",
                "ADDED LIST_ITEM Fourth step",
                // Only the checkbox changed.
                "MODIFIED LIST_ITEM Unit tests",
            ),
            changes("06-nested-lists"),
        )
    }

    @Test
    fun `07 tables pair rows by their first cell`() {
        assertEquals(
            listOf(
                "MODIFIED TABLE_ROW IDE Version Status Notes",
                // The added empty "Notes" cell makes every row of table 1 modified.
                "MODIFIED TABLE_ROW IntelliJ IDEA 2025.1 Sup",
                "MODIFIED TABLE_ROW PyCharm 2025.2 Supported",
                "MODIFIED TABLE_ROW WebStorm 2025.1 Supporte",
                "REMOVED TABLE_ROW Rider 2025.1 Unknown",
                "ADDED TABLE_ROW GoLand 2025.1 Supported",
                // Table 2: an alignment-only change is ignored.
            ),
            changes("07-tables"),
        )
    }

    @Test
    fun `08 code blocks`() = assertEquals(
        listOf(
            "MODIFIED CODE_LINE val diff = MarkdownDiff.",
            "ADDED CODE_LINE diff.blocks.forEach { pr",
            "MODIFIED CODE_LINE ./gradlew runIde",
            "MODIFIED CODE_FENCE json",
            "MODIFIED PARAGRAPH Inline code: FrameDiffTo",
        ),
        changes("08-code-blocks"),
    )

    @Test
    fun `09 links and images`() {
        // Link text and URL change, image src change, added image, and the paragraph whose
        // `[mp]` reference definition changed.
        assertEquals(
            listOf(
                "MODIFIED PARAGRAPH See the platform documen",
                "MODIFIED PARAGRAPH",
                "ADDED PARAGRAPH",
                "MODIFIED PARAGRAPH Reference link: Marketpl",
            ),
            changes("09-links-images"),
        )
        val sbs = html("09-links-images")
        // Same text, new URL: marked on both sides, the tooltip names the other side's URL.
        assertContains(
            sbs,
            "<a rel=\"nofollow\" href=\"https://github.com/example/markdown-review\" class=\"mdr-link-changed\" " +
                "title=\"New URL: https://github.com/example/markdown-review-plugin\">repository</a>",
        )
        assertContains(
            sbs,
            "<a rel=\"nofollow\" href=\"https://github.com/example/markdown-review-plugin\" class=\"mdr-link-changed\" " +
                "title=\"Previous URL: https://github.com/example/markdown-review\">repository</a>",
        )
        // A link whose text changed is shown by the word diff, not marked.
        assertContains(sbs, "<a rel=\"nofollow\" href=\"https://plugins.jetbrains.com/docs/intellij/\"><ins class=\"mdr-ins\">platform </ins>")
        // Image with the same alt text and a new src keeps its title and adds the old src.
        assertContains(
            sbs,
            "<img src=\"docs/screenshot-v2.png\" alt=\"Screenshot\" title=\"Diff view\nPrevious URL: docs/screenshot.png\" class=\"mdr-img-changed\" />",
        )
        assertContains(
            sbs,
            "<a rel=\"nofollow\" href=\"https://plugins.jetbrains.com/plugin/00000-markdown-review\" class=\"mdr-link-changed\" " +
                "title=\"Previous URL: https://plugins.jetbrains.com/\">Marketplace</a>",
        )
        assertFalse(sbs.contains("diagram.svg\" alt=\"Diagram\" class"))
        // The merged view shows the new side only, with the previous URL.
        val merged = html("09-links-images", ViewMode.MERGED)
        assertEquals(3, Regex("mdr-(link|img)-changed").findAll(merged).count())
        assertFalse(merged.contains("New URL:"))
    }

    @Test
    fun `10 blockquotes`() = assertEquals(
        listOf(
            "MODIFIED PARAGRAPH Markdown is intended to",
            "MODIFIED PARAGRAPH Note: this feature is st",
            "REMOVED PARAGRAPH It may be removed.",
            "MODIFIED PARAGRAPH Level 2, changed",
            "ADDED PARAGRAPH Level 3",
        ),
        changes("10-blockquotes"),
    )

    @Test
    fun `11 front matter`() {
        assertEquals(
            listOf(
                "MODIFIED FRONT_MATTER version: 1.1.0",
                "MODIFIED FRONT_MATTER tags: [intellij, markdow",
                "ADDED FRONT_MATTER draft: false",
            ),
            changes("11-front-matter"),
        )
        assertEquals(listOf("ADDED CODE_FENCE", "ADDED FRONT_MATTER title: No YAML header"), changes("11b-front-matter-added"))
    }

    @Test
    fun `12 whitespace-only changes are equal in both modes`() {
        val old = read("12-whitespace-only", "old.md")
        val new = read("12-whitespace-only", "new.md")
        assertFalse(MarkdownDiffEngine(DiffOptions(ignoreWhitespace = true)).diff(old, new).hasChanges)
        // Without ignoring whitespace, the soft break that became a hard break (trailing spaces) is the one change.
        val changes = MarkdownDiffEngine(DiffOptions(ignoreWhitespace = false)).diff(old, new).changes
        assertEquals(listOf(DiffStatus.MODIFIED), changes.map { it.status })
        assertEquals(listOf("\n"), changes[0].inline.filter { it.status == DiffStatus.ADDED }.map { it.text })
    }

    @Test
    fun `13 to 17 empty and identical files`() {
        assertTrue(diff("13-empty-both").blocks.isEmpty())
        assertTrue(diff("14-empty-to-content").blocks.all { it.status == DiffStatus.ADDED })
        assertTrue(diff("15-content-to-empty").blocks.all { it.status == DiffStatus.REMOVED })
        assertTrue(diff("16-whitespace-only-file").blocks.isEmpty())
        assertFalse(diff("17-identical").hasChanges)
    }

    @Test
    fun `18 mixed`() = assertEquals(
        listOf(
            "ADDED CODE_FENCE",
            "ADDED FRONT_MATTER title: Markdown Review",
            "MODIFIED PARAGRAPH An IntelliJ plugin for r",
            "MODIFIED LIST_ITEM Unified view with strike",
            "ADDED LIST_ITEM Per-block revert",
            "ADDED PARAGRAPH Text mode uses the IDE's",
            "ADDED CODE_LINE ./gradlew runIde",
            "REMOVED HEADING License",
            "REMOVED PARAGRAPH To be decided.",
            "ADDED TABLE_ROW Shortcut Action",
            "ADDED TABLE_ROW F7 Next change",
        ),
        changes("18-mixed"),
    )

    @Test
    fun `19 line endings`() {
        val d = diff("19-line-endings")
        assertFalse(d.hasChanges)
        // Lines are counted the same for CRLF and LF; offsets are in LF-normalized text.
        assertEquals(d.blocks.map { it.left!!.source.startLine }, d.blocks.map { it.right!!.source.startLine })
    }

    @Test
    fun `20 large file`() {
        val old = read("20-large-file", "old.md")
        val new = read("20-large-file", "new.md")
        val start = System.nanoTime()
        val result = CoreMarkdownDiffRenderer().render(RenderRequest(old, new, ViewMode.SIDE_BY_SIDE))
        val ms = (System.nanoTime() - start) / 1_000_000
        val counts = result.changes.groupingBy { it.status.name }.eachCount()
        assertEquals(mapOf("MODIFIED" to 14, "ADDED" to 2, "REMOVED" to 25), counts)
        assertTrue(ms < 3_000, "diff + render took $ms ms")
        val removedHeadings = diff("20-large-file").changes
            .filter { it.status == DiffStatus.REMOVED && it.left!!.kind == BlockKind.HEADING }
            .map { it.left!!.plainText }
        assertEquals(listOf("Section 450", "Section 451"), removedHeadings)
    }

    @Test
    fun `21 task lists`() {
        assertEquals(
            listOf(
                // Only the checkbox changed.
                "MODIFIED LIST_ITEM Update the changelog",
                "MODIFIED LIST_ITEM Tag the release on GitHu",
                "MODIFIED LIST_ITEM Plain item",
            ),
            changes("21-task-lists"),
        )
        assertTrue(diff("21-task-lists").changes[0].inline.all { it.status == DiffStatus.EQUAL })
    }

    @Test
    fun `22 strikethrough`() {
        val d = diff("22-strikethrough")
        assertEquals(
            listOf("MODIFIED PARAGRAPH The basic plan costs 10", "MODIFIED PARAGRAPH The trial lasts 14 days"),
            changes("22-strikethrough"),
        )
        assertEquals(listOf("12 EUR "), d.changes[0].inline.filter { it.status == DiffStatus.ADDED }.map { it.text })
        // Removing the strikethrough changes only the formatting.
        assertTrue(d.changes[1].inline.all { it.status == DiffStatus.EQUAL })
    }

    @Test
    fun `23 autolinks`() {
        val d = diff("23-autolinks")
        assertEquals(listOf("MODIFIED PARAGRAPH Docs: https://plugins.je"), changes("23-autolinks"))
        assertEquals(listOf("welcome"), d.changes[0].inline.filter { it.status == DiffStatus.REMOVED }.map { it.text })
        assertEquals(listOf("getting-started"), d.changes[0].inline.filter { it.status == DiffStatus.ADDED }.map { it.text })
    }

    @Test
    fun `24 footnotes`() {
        assertEquals(
            listOf(
                "MODIFIED PARAGRAPH Rendering uses commonmar",
                "REMOVED FOOTNOTE Word diff runs inside mo",
                "MODIFIED FOOTNOTE Version 0.30.",
                "ADDED FOOTNOTE The Chromium browser emb",
            ),
            changes("24-footnotes"),
        )
    }

    @Test
    fun `25 alerts`() {
        assertEquals(
            listOf(
                "MODIFIED PARAGRAPH Rendering runs in JCEF w",
                // Only the type changed; the content is equal.
                "MODIFIED ALERT Caution",
                "ADDED ALERT Important",
            ),
            changes("25-alerts"),
        )
        val content = diff("25-alerts").blocks.filter { it.right?.context?.alert == "IMPORTANT" }.map { it.status }
        assertEquals(listOf(DiffStatus.ADDED, DiffStatus.EQUAL), content)
    }

    @Test
    fun `26 relative images resolve against the fixture directory`() {
        val case = "26-relative-images"
        assertEquals(listOf("MODIFIED PARAGRAPH", "MODIFIED PARAGRAPH Text under the images, r"), changes(case))
        val dir = File(javaClass.getResource("/fixtures/$case/old.md")!!.toURI()).parentFile
        val base = dir.toURI().toString().let { if (it.endsWith("/")) it else "$it/" }
        val html = CoreMarkdownDiffRenderer().render(
            RenderRequest(read(case, "old.md"), read(case, "new.md"), ViewMode.SIDE_BY_SIDE, leftBaseUri = base, rightBaseUri = base),
        ).bodyHtml
        val logo = Regex("<img src=\"([^\"]+)\" alt=\"Logo\"").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(2, logo.size)
        logo.forEach { src ->
            assertTrue(src.startsWith("file:///"), src)
            assertTrue(File(java.net.URI(src)).isFile, "$src should point at the fixture's image")
        }
        assertFalse(html.contains("mdr-img-changed"))
        assertContains(html, "<img src=\"https://example.com/badge.svg\" alt=\"Remote\"")
    }

    @Test
    fun `27 mermaid diagram is one modified block with its line diff behind a toggle`() {
        assertEquals(listOf("MODIFIED MERMAID flowchart LR"), changes("27-mermaid").map { it.substringBefore("\n") })
        for (mode in ViewMode.entries) {
            val html = html("27-mermaid", mode)
            assertContains(html, "<details class=\"mdr-mermaid-source\"><summary>Show source changes</summary>")
            assertEquals(2, Regex("class=\"mdr-block mdr-\\w+ mdr-mermaid\"").findAll(html).count(), mode.name)
            // The source diff inside the toggle: the edge line with its word diff, the added node.
            assertContains(html, "<del class=\"mdr-del\">compare</del>")
            assertContains(html, "C --&gt; D[JCEF preview]")
        }
        // Nested source rows are not blocks: data-idx stays one per engine block.
        val rendered = CoreMarkdownDiffRenderer().render(
            RenderRequest(read("27-mermaid", "old.md"), read("27-mermaid", "new.md"), ViewMode.SIDE_BY_SIDE, ApiDiffOptions(collapseUnchanged = false)),
        )
        assertEquals(rendered.blocks.size, Regex("data-idx=").findAll(rendered.bodyHtml).count())
        assertEquals(LineRange(4, 9), rendered.changes.single().left)
    }

    @Test
    fun `27 mermaid with rendering off is diffed as a code block`() {
        val d = MarkdownDiffEngine(DiffOptions(mermaidDiagrams = false)).diff(read("27-mermaid", "old.md"), read("27-mermaid", "new.md"))
        assertEquals(
            listOf(DiffStatus.MODIFIED to BlockKind.CODE_LINE, DiffStatus.ADDED to BlockKind.CODE_LINE),
            d.changes.map { it.status to (it.right ?: it.left)!!.kind },
        )
        val fence = d.blocks.single { it.right?.kind == BlockKind.CODE_FENCE }
        assertEquals(DiffStatus.EQUAL, fence.status)
        val edge = d.changes[0].inline
        assertEquals(listOf("compare"), edge.filter { it.status == DiffStatus.REMOVED }.map { it.text.trim() })
        assertEquals("diff with HEAD", edge.filter { it.status == DiffStatus.ADDED }.joinToString("") { it.text }.trim())
        val html = CoreMarkdownDiffRenderer().render(
            RenderRequest(read("27-mermaid", "old.md"), read("27-mermaid", "new.md"), ViewMode.SIDE_BY_SIDE, ApiDiffOptions(renderMermaid = false)),
        ).bodyHtml
        assertFalse(html.contains("mdr-mermaid"))
    }

    @Test
    fun `28 mermaid diagrams`() {
        val d = diff("28-mermaid-diagrams")
        val diagrams = d.blocks.filter { (it.right ?: it.left)!!.kind == BlockKind.MERMAID }
            .map { it.status to (it.right ?: it.left)!!.plainText.lines().first() }
        assertEquals(
            listOf(
                DiffStatus.EQUAL to "sequenceDiagram",
                DiffStatus.MODIFIED to "flowchart TD",
                DiffStatus.REMOVED to "pie title Languages",
                DiffStatus.ADDED to "stateDiagram-v2",
                DiffStatus.MODIFIED to "flowchart LR",
            ),
            diagrams,
        )
        val html = html("28-mermaid-diagrams")
        assertEquals(2, Regex("<details class=\"mdr-mermaid-source\">").findAll(html).count())
        assertContains(html, "X --&gt; Y --&gt;")
    }

    /** [changes] with the engine run at [threshold], plus the statuses the core renderer reports at that threshold. */
    private fun changesAt(case: String, threshold: Double): List<String> {
        val engine = MarkdownDiffEngine(DiffOptions(pairingThreshold = threshold))
        val result = engine.diff(read(case, "old.md"), read(case, "new.md")).changes.map {
            val b = (it.right ?: it.left)!!
            "${it.status} ${b.kind} ${b.plainText.trim().take(24).trim()}".trim()
        }
        val rendered = CoreMarkdownDiffRenderer().render(
            RenderRequest(read(case, "old.md"), read(case, "new.md"), ViewMode.SIDE_BY_SIDE, ApiDiffOptions(pairingThreshold = threshold)),
        ).changes.map { it.status.name }
        assertEquals(result.map { it.substringBefore(' ') }, rendered, "renderer passes the threshold to the engine")
        return result
    }

    @Test
    fun `37 pairing threshold decides which rewritten paragraphs pair`() {
        assertEquals(
            listOf(
                "MODIFIED PARAGRAPH The build runs every nig",
                "MODIFIED PARAGRAPH Release notes are genera",
                "MODIFIED PARAGRAPH All tests must pass on W",
                "REMOVED PARAGRAPH Contributors sign the li",
                "ADDED PARAGRAPH Questions go to the disc",
            ),
            changesAt("37-pairing-threshold", 0.3),
        )
        assertEquals(
            listOf(
                "MODIFIED PARAGRAPH The build runs every nig",
                "REMOVED PARAGRAPH Release notes are writte",
                "REMOVED PARAGRAPH Tests must pass on Linux",
                "REMOVED PARAGRAPH Contributors sign the li",
                "ADDED PARAGRAPH Release notes are genera",
                "ADDED PARAGRAPH All tests must pass on W",
                "ADDED PARAGRAPH Questions go to the disc",
            ),
            changesAt("37-pairing-threshold", 0.7),
        )
    }

    @Test
    fun `38 a lone rewritten paragraph needs half the threshold`() {
        assertEquals(listOf("MODIFIED PARAGRAPH Download the plugin as a"), changesAt("38-pairing-threshold-lone", 0.3))
        assertEquals(
            listOf("REMOVED PARAGRAPH Install the plugin from", "ADDED PARAGRAPH Download the plugin as a"),
            changesAt("38-pairing-threshold-lone", 0.7),
        )
    }

    @Test
    fun `39 a replaced short list item does not pair through common words`() {
        val expected = listOf(
            "MODIFIED LIST_ITEM Side-by-side view with h",
            "REMOVED LIST_ITEM Export the diff to a PDF",
            "ADDED LIST_ITEM Revert a single block of",
        )
        assertEquals(expected, changesAt("39-short-list-items", ApiDiffOptions.DEFAULT_PAIRING_THRESHOLD))
        assertEquals(expected, changesAt("39-short-list-items", 0.7))
    }

    private fun added(d: DiffBlock) = d.inline.filter { it.status == DiffStatus.ADDED }.joinToString("") { it.text }.trim()
    private fun removed(d: DiffBlock) = d.inline.filter { it.status == DiffStatus.REMOVED }.joinToString("") { it.text }.trim()

    @Test
    fun `29 html blocks compare visible text`() {
        val d = diff("29-html-blocks")
        assertEquals(listOf("MODIFIED HTML Rendered Markdown diffs", "MODIFIED HTML Made with care."), changes("29-html-blocks"))
        assertEquals("IntelliJ", removed(d.changes[0]))
        assertEquals("JetBrains IDEs", added(d.changes[0]))
        assertTrue(d.changes[1].inline.all { it.status == DiffStatus.EQUAL })
        val html = html("29-html-blocks")
        assertContains(html, "<img src=\"logo.png\" alt=\"Logo\" width=\"120\">")
        assertContains(html, "<ins class=\"mdr-ins\">JetBrains IDEs</ins>")
        assertContains(html, "data-change-note=\"HTML changed\"")
    }

    @Test
    fun `30 inline html`() {
        val d = diff("30-inline-html")
        assertEquals(listOf("MODIFIED PARAGRAPH Press Cmd+D to open the", "MODIFIED PARAGRAPH Water is H2O and the are"), changes("30-inline-html"))
        assertEquals("Ctrl", removed(d.changes[0]))
        assertEquals("Cmd", added(d.changes[0]))
        val html = html("30-inline-html")
        assertContains(html, "<kbd><ins class=\"mdr-ins\">Cmd</ins></kbd>+<kbd>D</kbd>")
        assertContains(html, "H<sub>2</sub>O")
        assertContains(html, "<ins class=\"mdr-ins\">π </ins>r<sup>2</sup>")
        assertEquals("π", added(d.changes[1]))
    }

    @Test
    fun `31 dangerous html is sanitized`() {
        val d = diff("31-html-sanitizer")
        assertTrue(d.changes.all { it.status == DiffStatus.ADDED })
        assertEquals(2, d.blocks.count { it.status == DiffStatus.EQUAL })
        for (mode in ViewMode.entries) {
            val html = html("31-html-sanitizer", mode).lowercase()
            for (bad in listOf("<script", "<iframe", "onclick", "onerror", "onmouseover", "javascript:", "jav&#x61;script", "style=", "alert(")) {
                assertFalse(bad in html, "$bad in $mode")
            }
            assertContains(html, "<img src=\"x.png\">")
            assertContains(html, "<img src=\"y.png\">")
            assertContains(html, "<a href=\"https://example.com/ok\">kept</a>")
            assertContains(html, "<a title=\"t\">link</a>")
            assertContains(html, "<span>span</span>")
        }
    }

    @Test
    fun `32 details`() {
        val d = diff("32-details")
        assertEquals(
            listOf("MODIFIED PARAGRAPH The rendered viewer open", "MODIFIED HTML ShortOne-block answer, c"),
            changes("32-details"),
        )
        val summaries = d.blocks.filter { it.right?.kind == BlockKind.DETAILS }
        assertEquals(listOf("How do I open the diff?", "Is it free?"), summaries.map { it.right!!.plainText.trim() })
        assertTrue(summaries.all { it.status == DiffStatus.EQUAL })
        assertEquals(1, d.changes[0].right!!.context.details)
        assertEquals("instead of", added(d.changes[0]))
        assertTrue(d.blocks.none { it.right?.rawText?.trim() == "</details>" })
        for (mode in ViewMode.entries) {
            val html = html("32-details", mode)
            assertEquals(2, Regex("<details class=\"mdr-details\"").findAll(html).count())
            assertContains(html, "<details class=\"mdr-details\" open><summary class=\"mdr-details-summary\">")
            assertContains(html, "<details class=\"mdr-details\"><summary class=\"mdr-details-summary\">")
            assertContains(html, "<details open><summary>Short</summary>")
        }
    }

    @Test
    fun `33 html comments`() {
        val d = diff("33-html-comments")
        assertEquals(
            listOf(
                "MODIFIED HTML_COMMENT TODO: add screenshots of",
                "ADDED HTML_COMMENT Generated section starts",
                "MODIFIED PARAGRAPH Paragraph with  comment.",
            ),
            changes("33-html-comments"),
        )
        assertEquals("of the merged view", added(d.changes[0]))
        assertTrue(d.changes[2].inline.all { it.status == DiffStatus.EQUAL })
        val html = html("33-html-comments")
        assertContains(html, "<div class=\"mdr-block mdr-modified mdr-comment\" title=\"HTML comment\"><span class=\"mdr-comment-text\">TODO: add screenshots<ins class=\"mdr-ins\"> of the merged view</ins></span>")
        assertContains(html, "data-change-note=\"comment changed\"")
        assertFalse(html.contains("inline note"))
    }

    @Test
    fun `34 hard line breaks`() {
        val d = diff("34-hard-line-breaks")
        assertEquals(
            listOf("MODIFIED PARAGRAPH Line one\nLine two", "MODIFIED PARAGRAPH Street 1 City", "MODIFIED PARAGRAPH First Second"),
            changes("34-hard-line-breaks"),
        )
        assertEquals(listOf("\n"), d.changes[0].inline.filter { it.status == DiffStatus.ADDED }.map { it.text })
        assertEquals(listOf("\n"), d.changes[1].inline.filter { it.status == DiffStatus.REMOVED }.map { it.text })
        val html = html("34-hard-line-breaks")
        assertContains(html, "Line one<ins class=\"mdr-ins mdr-ws mdr-ws-break\"></ins><br />")
        assertContains(html, "Street 1<del class=\"mdr-del mdr-ws mdr-ws-break\"></del><br />")
        val merged = html("34-hard-line-breaks", ViewMode.MERGED)
        assertContains(merged, "<del class=\"mdr-del mdr-ws mdr-ws-break\"></del>")
    }

    @Test
    fun `35 thematic breaks`() = assertEquals(
        listOf("REMOVED THEMATIC_BREAK", "ADDED THEMATIC_BREAK", "ADDED PARAGRAPH Footer."),
        changes("35-thematic-breaks"),
    )

    @Test
    fun `36 emoji shortcodes`() {
        val d = diff("36-emoji")
        assertEquals(
            listOf("MODIFIED PARAGRAPH Build fails \u274c and review", "MODIFIED PARAGRAPH Unknown codes stay as te"),
            changes("36-emoji"),
        )
        assertEquals("passes \u2705", removed(d.changes[0]))
        assertEquals("fails \u274c", added(d.changes[0]))
        assertContains(d.changes[0].right!!.plainText, "welcome \ud83d\ude04.")
        assertContains(d.changes[1].right!!.plainText, ":still_not_an_emoji: and 10:30:00")
        assertEquals("Status \ud83d\ude80", d.blocks[0].right!!.plainText)
        assertContains(html("36-emoji"), "<code>:smile:</code>")
    }

    @Test
    fun `40 math formulas are compared as units (P2_4)`() {
        val d = diff("40-math")
        assertEquals(
            listOf(
                "MODIFIED PARAGRAPH",
                "MODIFIED MATH",
                "ADDED PARAGRAPH",
                "ADDED MATH",
                "ADDED PARAGRAPH",
                "MODIFIED PARAGRAPH",
            ),
            d.changes.map { "${it.status} ${(it.right ?: it.left)!!.kind.name}" },
        )
        // A block formula is one block holding the whole formula.
        val formulas = d.blocks.filter { (it.right ?: it.left)!!.kind.name == "MATH" }
        assertEquals(
            listOf(
                DiffStatus.EQUAL to "E = mc^2",
                DiffStatus.MODIFIED to "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2 a}",
                DiffStatus.ADDED to "e^{i\\pi} + 1 = 0",
            ),
            formulas.map { it.status to (it.right ?: it.left)!!.plainText.trim() },
        )
        assertEquals("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}", formulas[1].left!!.plainText.trim())
        // An inline formula is one segment of the word diff, never split into words.
        fun formula(text: String) = text.trim().removeSurrounding("\$")
        val circle = d.changes[0]
        assertEquals(listOf("C = 2 \\pi r"), circle.inline.filter { it.status == DiffStatus.REMOVED }.map { formula(it.text) })
        assertEquals(listOf("C = \\tau r"), circle.inline.filter { it.status == DiffStatus.ADDED }.map { formula(it.text) })
        // Prices are not formulas: a plain word diff.
        assertEquals("10", removed(d.changes[5]))
        assertEquals("12", added(d.changes[5]))
    }

    @Test
    fun `40 math formulas render as inert monospace source (P2_4, P2_8)`() {
        for (mode in ViewMode.entries) {
            val html = html("40-math", mode)
            assertFalse(html.contains("<script"), mode.name)
            assertContains(html, "<span class=\"mdr-math mdr-math-inline\">", message = mode.name)
            assertContains(html, "<div class=\"mdr-math mdr-math-display\">", message = mode.name)
            assertContains(html, "e^{i\\pi} + 1 = 0", message = mode.name)
            // The unparsable formula is still a formula node; drawing it fails in the page, not here.
            assertContains(html, "\\frac{1}{", message = mode.name)
            // A changed block formula has the same toggle to its source diff as a changed diagram.
            assertContains(html, "<details class=\"mdr-math-source\"><summary>Show source changes</summary>", message = mode.name)
            // Code spans keep their dollar signs as code.
            assertContains(html, "<code>\$x\$</code>", message = mode.name)
        }
        val sideBySide = html("40-math")
        // Two inline formulas on each side of the circle paragraph, one in the added paragraph.
        assertEquals(5, Regex("class=\"mdr-math mdr-math-inline\"").findAll(sideBySide).count())
        // Two block formulas on the left, three on the right.
        assertEquals(5, Regex("class=\"mdr-math mdr-math-display\"").findAll(sideBySide).count())
        assertFalse(Regex("mdr-math[^>]*>[^<]*\\\$5").containsMatchIn(sideBySide))
    }

    @Test
    fun `41 wiki links are links (P3_6)`() {
        val d = diff("41-wiki-links")
        assertEquals(
            listOf("MODIFIED PARAGRAPH The setup lives on the s", "MODIFIED PARAGRAPH Details are in Configura"),
            changes("41-wiki-links"),
        )
        assertEquals("install", removed(d.changes[0]))
        assertEquals("setup", added(d.changes[0]))
        // Same visible text, new target: only the link marks the change.
        assertTrue(d.changes[1].inline.none { it.status != DiffStatus.EQUAL })
        assertEquals("See Getting Started before you begin.", d.blocks[1].right!!.plainText)
        assertEquals(
            "Not links: [[code]], [[escaped]], ![[diagram.png]] and [[TOC]].",
            d.blocks.last().right!!.plainText,
        )
        for (mode in ViewMode.entries) {
            val html = html("41-wiki-links", mode)
            assertContains(html, "<a rel=\"nofollow\" href=\"Getting%20Started\">Getting Started</a>", message = mode.name)
            assertContains(html, "<code>[[code]]</code>", message = mode.name)
            assertFalse(html.contains("href=\"code\"") || html.contains("href=\"escaped\"") || html.contains("href=\"diagram.png\""), mode.name)
            assertFalse(html.contains("href=\"TOC\""), mode.name)
        }
        val sideBySide = html("41-wiki-links")
        assertContains(sideBySide, "<a rel=\"nofollow\" href=\"Install%20Guide\">")
        assertContains(sideBySide, "<a rel=\"nofollow\" href=\"Configuration#Network%20proxy\" class=\"mdr-link-changed\" ")
        assertContains(sideBySide, "<a rel=\"nofollow\" href=\"Configuration#Proxy\" class=\"mdr-link-changed\" ")
    }
}
