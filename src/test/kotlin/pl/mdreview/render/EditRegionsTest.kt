package pl.mdreview.render

import pl.mdreview.api.BlockInfo
import pl.mdreview.api.BlockStatus
import pl.mdreview.api.DiffOptions
import pl.mdreview.api.LineRange
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.TextEdit
import pl.mdreview.api.ViewMode
import pl.mdreview.engine.BlockExtractor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditRegionsTest {

    private val renderer = CoreMarkdownDiffRenderer()

    private fun render(left: String, right: String) =
        renderer.render(RenderRequest(left, right, ViewMode.SIDE_BY_SIDE, DiffOptions(collapseUnchanged = false)))

    private fun revert(left: String, right: String, block: BlockInfo): String {
        val region = block.edit!!
        return TextEdit.replaceLines(right, region.right, left, region.left).applyTo(right)
    }

    private fun apply(left: String, right: String, block: BlockInfo): String {
        val region = block.edit!!
        return TextEdit.replaceLines(left, region.left, right, region.right).applyTo(left)
    }

    private fun revertOne(left: String, right: String, pick: (List<BlockInfo>) -> BlockInfo): String =
        revert(left, right, pick(render(left, right).changes))

    /** Reverts one change at a time, re-rendering in between, until no change that can be reverted is left. */
    private fun revertAll(left: String, right: String, pick: (List<BlockInfo>) -> BlockInfo): String {
        var text = right
        repeat(200) {
            val changes = render(left, text).changes.filter { it.edit != null }
            if (changes.isEmpty()) return text
            text = revert(left, text, pick(changes))
        }
        error("Reverting did not converge:\n$text")
    }

    /** Applies one change at a time to the left side until no change is left. */
    private fun applyAll(left: String, right: String): String {
        var text = left
        repeat(200) {
            val changes = render(text, right).changes
            if (changes.isEmpty()) return text
            text = apply(text, right, changes.first())
        }
        error("Applying did not converge:\n$text")
    }

    private fun fixture(case: String, name: String): String {
        val url = javaClass.getResource("/fixtures/$case/$name") ?: error("Missing fixture $case/$name")
        return BlockExtractor.normalizeLineEndings(File(url.toURI()).readText())
    }

    @Test
    fun `equal blocks have no region`() {
        val diff = render("# A\n\ntext\n", "# A\n\nother words entirely\n")
        assertNull(diff.blocks[0].edit)
    }

    @Test
    fun `modified block exchanges its own lines`() {
        val left = "# A\n\nold text here\n"
        val right = "# A\n\nnew text here\n"
        val block = render(left, right).changes.single()
        assertEquals(BlockStatus.MODIFIED, block.status)
        assertEquals(LineRange(2, 3), block.edit!!.right)
        assertEquals(left, revert(left, right, block))
        assertEquals(right, apply(left, right, block))
    }

    @Test
    fun `added paragraph is deleted with its blank line at the start, middle and end`() {
        val base = "A one\n\nB two\n\nC three\n"
        for (right in listOf("X new\n\n$base", "A one\n\nX new\n\nB two\n\nC three\n", "${base}\nX new\n")) {
            assertEquals(base, revertOne(base, right) { it.single() }, "right: $right")
        }
    }

    @Test
    fun `removed paragraph is inserted with its blank line at the start, middle and end`() {
        val right = "A one\n\nB two\n\nC three\n"
        for (left in listOf("X old\n\n$right", "A one\n\nX old\n\nB two\n\nC three\n", "${right}\nX old\n")) {
            assertEquals(left, revertOne(left, right) { it.single() }, "left: $left")
        }
    }

    @Test
    fun `text without a final line break`() {
        assertEquals("A one\n\nB two", revertOne("A one\n\nB two", "A one") { it.single() })
        assertEquals("A one", revertOne("A one", "A one\n\nB two") { it.single() })
    }

    @Test
    fun `empty side`() {
        assertEquals("", revertAll("", "# Title\n\ntext\n") { it.first() })
        assertEquals("# Title\n\ntext\n", revertAll("# Title\n\ntext\n", "") { it.first() })
    }

    @Test
    fun `list items keep the list tight`() {
        val left = "Intro\n\n- a\n- b\n- c\n"
        for (right in listOf("Intro\n\n- x\n- a\n- b\n- c\n", "Intro\n\n- a\n- b\n- x\n- c\n", "Intro\n\n- a\n- b\n- c\n- x\n")) {
            assertEquals(left, revertOne(left, right) { it.single() }, "right: $right")
            assertEquals(right, revertOne(right, left) { it.single() }, "left: $right")
        }
    }

    @Test
    fun `code line added at the end stays inside the fence`() {
        val left = "```\na\n```\n\nafter\n"
        val right = "```\na\nb\n```\n\nafter\n"
        assertEquals(left, revertOne(left, right) { it.single() })
        assertEquals(right, revertOne(right, left) { it.single() })
    }

    @Test
    fun `paragraph removed after a code block goes after the closing fence`() {
        val left = "```\nx\n```\n\nB text\n"
        val right = "```\nx\n```\n"
        assertEquals(left, revertOne(left, right) { it.single() })
    }

    @Test
    fun `whole code block reverts from any of its blocks`() {
        val left = "Intro\n"
        val right = "Intro\n\n```kotlin\nval a = 1\nval b = 2\n```\n"
        val changes = render(left, right).changes
        assertEquals(3, changes.size)
        for (block in changes) assertEquals(left, revert(left, right, block))
        assertEquals(right, revertOne(right, left) { it[1] })
    }

    @Test
    fun `whole table reverts with its delimiter row`() {
        val left = "Intro\n"
        val right = "Intro\n\n| A | B |\n|---|---|\n| 1 | 2 |\n"
        for (block in render(left, right).changes) assertEquals(left, revert(left, right, block))
        assertEquals(right, revertOne(right, left) { it.first() })
    }

    @Test
    fun `table row added to an existing table`() {
        val left = "| A | B |\n|---|---|\n| 1 | 2 |\n"
        val right = "| A | B |\n|---|---|\n| 0 | 0 |\n| 1 | 2 |\n"
        assertEquals(left, revertOne(left, right) { it.single() })
        assertEquals(right, revertOne(right, left) { it.single() })
    }

    @Test
    fun `front matter added at the start`() {
        val left = "# Title\n"
        val right = "---\ntitle: x\n---\n\n# Title\n"
        for (block in render(left, right).changes) assertEquals(left, revert(left, right, block))
    }

    @Test
    fun `mermaid diagram reverts as one block with its fences`() {
        val left = "Intro\n\n```mermaid\ngraph TD\n  A --> B\n```\n\nafter\n"
        val modified = "Intro\n\n```mermaid\ngraph TD\n  A --> C\n  C --> B\n```\n\nafter\n"
        val block = render(left, modified).changes.single()
        assertEquals(BlockStatus.MODIFIED, block.status)
        assertEquals(left, revert(left, modified, block))
        assertEquals(modified, apply(left, modified, block))

        val without = "Intro\n\nafter\n"
        assertEquals(without, revertOne(without, left) { it.single() })
        assertEquals(left, revertOne(left, without) { it.single() })
    }

    @Test
    fun `quoted paragraphs`() {
        val left = "> one\n>\n> two\n"
        val right = "> one\n"
        assertEquals(left, revertOne(left, right) { it.single() })
        assertEquals(right, revertOne(right, left) { it.single() })
    }

    @Test
    fun `fixture 18 reverted change by change gives the left side`() {
        val left = fixture("18-mixed", "old.md")
        val right = fixture("18-mixed", "new.md")
        assertEquals(left, revertAll(left, right) { it.first() })
        assertEquals(left, revertAll(left, right) { it.last() })
        assertEquals(right, applyAll(left, right))
    }

    @Test
    fun `every fixture reverts to its left side`() {
        val root = File(javaClass.getResource("/fixtures")!!.toURI())
        val cases = root.listFiles()!!.filter { File(it, "old.md").exists() }.map { it.name }.sorted()
        val failed = cases.filter { case ->
            val left = fixture(case, "old.md")
            val right = fixture(case, "new.md")
            val result = runCatching { revertAll(left, right) { it.first() } }.getOrNull()
            if (case in NOT_EXACT) result == null || render(left, result).changes.any { it.edit != null }
            else result != left
        }
        assertEquals(emptyList(), failed)
    }

    @Test
    fun `a change only in a reference definition has no region`() {
        val left = "See [docs][d].\n\n[d]: https://a.example/\n"
        val right = "See [docs][d].\n\n[d]: https://b.example/\n"
        val block = render(left, right).changes.single()
        assertNull(block.edit)
    }

    @Test
    fun `setext heading keeps its underline`() {
        val left = "Title\n-----\n\ntext\n"
        val right = "## Title changed\n\ntext\n"
        assertEquals(left, revertOne(left, right) { it.single() })
        assertEquals(right, revertOne(right, left) { it.single() })
    }

    private companion object {
        /**
         * Fixtures whose sides stay different after every change is reverted, because the difference is not in
         * any block: syntax the diff treats as equal (05: setext vs ATX heading), whitespace it ignores (12, 16), a
         * table's column alignment (07), a link reference definition (09), the hard break syntax (34) and the
         * thematic break syntax (35). They must still end with no change left that the buttons could revert.
         */
        val NOT_EXACT = setOf(
            "05-headings", "07-tables", "09-links-images", "12-whitespace-only", "16-whitespace-only-file",
            "34-hard-line-breaks", "35-thematic-breaks",
        )
    }

    @Test
    fun `details content, summary and whole group revert and apply`() {
        val body = "<details>\n<summary>More</summary>\n\nFirst.\n\nLast.\n\n</details>\n"
        val cases = listOf(
            "Intro.\n\n$body\nEnd.\n" to "Intro.\n\n${body.replace("Last.", "Last, changed.")}\nEnd.\n",
            "Intro.\n\n$body\nEnd.\n" to "Intro.\n\n${body.replace("Last.\n\n", "Last.\n\nAdded.\n\n")}\nEnd.\n",
            "Intro.\n\n$body\nEnd.\n" to "Intro.\n\n${body.replace("More", "Much more")}\nEnd.\n",
            "Intro.\n\nEnd.\n" to "Intro.\n\n$body\nEnd.\n",
            "Intro.\n\n$body\nEnd.\n" to "Intro.\n\nEnd.\n",
        )
        for ((left, right) in cases) {
            assertEquals(left, revertAll(left, right) { it.first() }, right)
            assertEquals(left, revertAll(left, right) { it.last() }, right)
            assertEquals(right, applyAll(left, right), right)
        }
    }

    @Test
    fun `html comments and html blocks revert`() {
        val left = "# T\n\n<!-- note -->\n\n<div align=\"center\">Hi</div>\n\nText.\n"
        val right = "# T\n\n<!-- new note -->\n\n<div align=\"left\">Hi there</div>\n\n<!-- added -->\n\nText.\n"
        assertEquals(left, revertAll(left, right) { it.first() })
        assertEquals(left, revertAll(left, right) { it.last() })
        assertEquals(right, applyAll(left, right))
    }
}
