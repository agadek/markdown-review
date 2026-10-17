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
        // A soft break turning into a hard break is accepted as equal too.
        assertFalse(MarkdownDiffEngine(DiffOptions(ignoreWhitespace = false)).diff(old, new).hasChanges)
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
}
