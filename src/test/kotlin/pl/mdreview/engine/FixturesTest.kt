package pl.mdreview.engine

import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
import pl.mdreview.render.CoreMarkdownDiffRenderer
import java.io.File
import kotlin.test.Test
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
        // Link text and URL change, image src change, added image. A changed reference definition
        // is not detected: the paragraph using it compares equal.
        assertEquals(
            listOf("MODIFIED PARAGRAPH See the platform documen", "MODIFIED PARAGRAPH", "ADDED PARAGRAPH"),
            changes("09-links-images"),
        )
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
}
