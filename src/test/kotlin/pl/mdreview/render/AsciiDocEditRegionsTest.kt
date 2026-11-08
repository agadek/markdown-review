package pl.mdreview.render

import org.asciidoctor.Asciidoctor
import pl.mdreview.api.BlockInfo
import pl.mdreview.api.DiffOptions
import pl.mdreview.api.DocumentFormat
import pl.mdreview.api.LineRange
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.TextEdit
import pl.mdreview.api.ViewMode
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffEngine
import pl.mdreview.engine.DiffOptions as EngineOptions
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.asciidoc.AsciiDocBlockSource
import pl.mdreview.engine.asciidoc.AsciidoctorProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AsciiDoc edit regions (P4.5, FR-20): prefix lines, code blocks, containers, tables, lists, header, comments and
 * includes, and the round trip over every AsciiDoc fixture.
 */
class AsciiDocEditRegionsTest {

    private val provider: AsciidoctorProvider = TestProvider
    private val source = AsciiDocBlockSource(provider)
    private val renderer = CoreMarkdownDiffRenderer(provider)

    private fun render(left: String, right: String) =
        renderer.render(
            RenderRequest(left, right, ViewMode.SIDE_BY_SIDE, DiffOptions(collapseUnchanged = false), format = DocumentFormat.ASCIIDOC),
        )

    private fun diff(left: String, right: String) = DiffEngine(EngineOptions(), source).diff(left, right)

    private fun regions(left: String, right: String): List<pl.mdreview.api.EditRegion?> {
        val d = diff(left, right)
        return EditRegions(d, left, right, AsciiDocRegionRules).compute()
    }

    private fun regionOf(left: String, right: String, kind: BlockKind, text: String? = null): pl.mdreview.api.EditRegion {
        val d = diff(left, right)
        val k = d.blocks.indices.single { i ->
            val b = d.blocks[i].right ?: d.blocks[i].left!!
            d.blocks[i].status != DiffStatus.EQUAL && b.kind == kind && (text == null || b.plainText == text)
        }
        return assertNotNull(regions(left, right)[k], "no region for ${d.blocks[k]}")
    }

    private fun revert(left: String, right: String, block: BlockInfo): String {
        val region = block.edit!!
        return TextEdit.replaceLines(right, region.right, left, region.left).applyTo(right)
    }

    private fun apply(left: String, right: String, block: BlockInfo): String {
        val region = block.edit!!
        return TextEdit.replaceLines(left, region.left, right, region.right).applyTo(left)
    }

    private fun applyAll(left: String, right: String): String {
        var text = left
        repeat(300) {
            val blocks = render(text, right).blocks.filter { it.edit != null }
            if (blocks.isEmpty()) return text
            text = apply(text, right, blocks.first())
        }
        error("Applying did not converge")
    }

    private fun revertAll(left: String, right: String): String {
        var text = right
        repeat(300) {
            val blocks = render(left, text).blocks.filter { it.edit != null }
            if (blocks.isEmpty()) return text
            text = revert(left, text, blocks.first())
        }
        error("Reverting did not converge")
    }

    private fun fixture(case: String, name: String): String {
        val url = javaClass.getResource("/fixtures/$case/$name") ?: error("Missing fixture $case/$name")
        return File(url.toURI()).readText().replace("\r\n", "\n")
    }

    private fun cases(): List<String> {
        val root = File(javaClass.getResource("/fixtures")!!.toURI())
        return root.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "old.adoc").isFile && File(it, "new.adoc").isFile }
            .map { it.name }
            .sorted()
    }

    private fun tree(text: String): List<Triple<BlockKind, String, String>> =
        source.extract(text, EngineOptions()).map { Triple(it.kind, it.key, it.plainText) }

    @Test
    fun `a source attribute belongs to the modified code fence`() {
        val left = "= T\n\n[source,java]\n----\nval a = 1\n----\n"
        val right = left.replace("[source,java]", "[source,kotlin]")
        val region = regionOf(left, right, BlockKind.CODE_FENCE)
        assertEquals(LineRange(2, 3), region.left)
        assertEquals(LineRange(2, 3), region.right)
        assertEquals(left, revert(left, right, render(left, right).changes.single()))
        assertEquals(right, apply(left, right, render(left, right).changes.single()))
    }

    @Test
    fun `a block title is its own line when it changes`() {
        val left = "= T\n\n.Topic\n====\nText.\n====\n"
        val right = left.replace(".Topic", ".New topic")
        val d = diff(left, right)
        val k = d.blocks.indexOfFirst { (it.right ?: it.left)!!.kind == BlockKind.BLOCK_TITLE }
        assertEquals(LineRange(2, 3), assertNotNull(regions(left, right)[k]).left)
        assertEquals(LineRange(2, 3), assertNotNull(regions(left, right)[k]).right)
        assertEquals(left, revertAll(left, right))
        assertEquals(right, applyAll(left, right))
    }

    @Test
    fun `an added block title is inserted on its own line`() {
        val left = "= T\n\n====\nText.\n====\n"
        val right = "= T\n\n.Added\n====\nText.\n====\n"
        val region = regionOf(left, right, BlockKind.BLOCK_TITLE)
        assertEquals(LineRange(2, 3), region.right)
        assertEquals(LineRange(2, 2), region.left)
        assertEquals(right, applyAll(left, right))
        assertEquals(left, revertAll(left, right))
    }

    @Test
    fun `a whole delimited code block is one region with both delimiters`() {
        val left = "Intro\n"
        val right = "Intro\n\n[source]\n----\na\nb\n----\n"
        val d = diff(left, right)
        val changed = d.blocks.indices.filter { d.blocks[it].status != DiffStatus.EQUAL }
        assertEquals(3, changed.size)
        val all = regions(left, right)
        assertEquals(1, changed.map { all[it] }.distinct().size)
        val region = assertNotNull(all[changed.first()])
        val lines = right.split('\n').subList(region.right.start, region.right.end)
        assertTrue(lines.contains("[source]"), lines.toString())
        assertTrue(lines.contains("a") && lines.contains("b"), lines.toString())
        assertEquals(2, lines.count { it == "----" }, lines.toString())
        assertEquals(right, applyAll(left, right))
        assertEquals(left, revertAll(left, right))
    }

    @Test
    fun `a modified code line exchanges only its line`() {
        val left = "[source]\n----\nval a = 1\nval b = 2\n----\n"
        val right = left.replace("val b = 2", "val b = 3")
        val region = regionOf(left, right, BlockKind.CODE_LINE, "val b = 3")
        assertEquals(LineRange(3, 4), region.left)
        assertEquals(LineRange(3, 4), region.right)
    }

    @Test
    fun `an added container is one region and an inner change never touches its delimiters`() {
        val left = "Intro\n"
        val right = "Intro\n\n.Example\n====\nText.\n====\n"
        val d = diff(left, right)
        val changed = d.blocks.indices.filter { d.blocks[it].status != DiffStatus.EQUAL }
        val all = regions(left, right)
        assertEquals(1, changed.map { all[it] }.distinct().size)
        val region = assertNotNull(all[changed.first()])
        val lines = right.split('\n').subList(region.right.start, region.right.end)
        assertTrue(lines.contains(".Example") && lines.count { it == "====" } == 2, lines.toString())
        assertEquals(right, applyAll(left, right))
        assertEquals(left, revertAll(left, right))

        val modified = right.replace("Text.", "Changed text.")
        val inner = regionOf(right, modified, BlockKind.PARAGRAPH, "Changed text.")
        assertEquals(LineRange(4, 5), inner.left)
        assertEquals(LineRange(4, 5), inner.right)
    }

    @Test
    fun `a table row exchanges its line and a whole table its delimiters`() {
        val table = "[cols=\"1,1\"]\n|===\n| A | B\n| 1 | 2\n|===\n"
        val modified = table.replace("| 1 | 2", "| 1 | 3")
        val row = regionOf(table, modified, BlockKind.TABLE_ROW, "1 | 3")
        assertEquals(LineRange(3, 4), row.left)
        assertEquals(LineRange(3, 4), row.right)

        val left = "Intro\n"
        val added = "Intro\n\n$table"
        val d = diff(left, added)
        val changed = d.blocks.indices.filter { d.blocks[it].status != DiffStatus.EQUAL }
        val all = regions(left, added)
        assertEquals(1, changed.map { all[it] }.distinct().size)
        val region = assertNotNull(all[changed.first()])
        val lines = added.split('\n').subList(region.right.start, region.right.end)
        assertTrue(lines.contains("[cols=\"1,1\"]") && lines.count { it == "|===" } == 2, lines.toString())
        assertEquals(added, applyAll(left, added))
        assertEquals(left, revertAll(left, added))
    }

    @Test
    fun `a header row whose table options changed exchanges the attribute line`() {
        val left = "[cols=\"1,1\"]\n|===\n| A | B\n| 1 | 2\n|===\n"
        val right = left.replace("[cols=\"1,1\"]", "[cols=\"1,1\",options=\"header\"]")
        val d = diff(left, right)
        val k = d.blocks.indexOfFirst { it.right?.key?.startsWith("th:") == true || it.left?.key?.startsWith("th:") == true }
        val region = assertNotNull(regions(left, right)[k])
        assertEquals(0, region.left.start)
        assertEquals(0, region.right.start)
        assertEquals(right, applyAll(left, right))
        assertEquals(left, revertAll(left, right))
    }

    @Test
    fun `a list item with a continuation is one region and a modified item its own line`() {
        val left = "Intro\n\n* Kept\n"
        val right = "Intro\n\n* Kept\n\n* New\n+\nAttached.\n"
        val d = diff(left, right)
        val changed = d.blocks.indices.filter { d.blocks[it].status != DiffStatus.EQUAL }
        assertEquals(2, changed.size)
        val all = regions(left, right)
        assertEquals(1, changed.map { all[it] }.distinct().size)
        val lines = right.split('\n').subList(all[changed.first()]!!.right.start, all[changed.first()]!!.right.end)
        assertTrue(lines.any { it == "* New" } && lines.any { it == "+" } && lines.any { it == "Attached." }, lines.toString())
        assertEquals(right, applyAll(left, right))
        assertEquals(left, revertAll(left, right))

        val modified = "* First item text\n* Next item\n".replace("First item text", "First item words")
        val item = regionOf("* First item text\n* Next item\n", modified, BlockKind.LIST_ITEM, "First item words")
        assertEquals(LineRange(0, 1), item.left)
        assertEquals(LineRange(0, 1), item.right)
    }

    @Test
    fun `a header added or removed is one region and a changed header line its own line`() {
        val left = "== Section\n\nText.\n"
        val right = "= Title\n:attr: v\n\n== Section\n\nText.\n"
        val d = diff(left, right)
        val changed = d.blocks.indices.filter { d.blocks[it].status != DiffStatus.EQUAL }
        assertEquals(2, changed.size)
        val all = regions(left, right)
        assertEquals(1, changed.map { all[it] }.distinct().size)
        val region = assertNotNull(all[changed.first()])
        val lines = right.split('\n').subList(region.right.start, region.right.end)
        assertTrue(lines.contains("= Title") && lines.contains(":attr: v"), lines.toString())
        assertEquals(right, applyAll(left, right))
        assertEquals(left, revertAll(left, right))

        val changedTitle = right.replace("= Title", "= New title")
        val title = regionOf(right, changedTitle, BlockKind.HEADING, "New title")
        assertEquals(LineRange(0, 1), title.left)
        assertEquals(LineRange(0, 1), title.right)
    }

    @Test
    fun `a passthrough block is one region with its delimiters`() {
        val left = "Intro\n"
        val right = "Intro\n\n++++\n<p>Hi.</p>\n++++\n"
        val region = regionOf(left, right, BlockKind.HTML)
        val lines = right.split('\n').subList(region.right.start, region.right.end)
        assertEquals(2, lines.count { it == "++++" }, lines.toString())
        assertEquals(right, applyAll(left, right))
        assertEquals(left, revertAll(left, right))
    }

    @Test
    fun `a block comment exchanges all its lines and an include its own line`() {
        val left = "////\nold\n////\n"
        val right = "////\nnew\n////\n"
        val comment = regionOf(left, right, BlockKind.HTML_COMMENT)
        assertEquals(LineRange(0, 3), comment.left)
        assertEquals(LineRange(0, 3), comment.right)

        val without = "Intro\n"
        val withInclude = "Intro\n\ninclude::chapters/setup.adoc[]\n"
        val include = regionOf(without, withInclude, BlockKind.INCLUDE)
        val includeLines = withInclude.split('\n').subList(include.right.start, include.right.end)
        assertTrue(includeLines.contains("include::chapters/setup.adoc[]"), includeLines.toString())
        assertEquals(withInclude, applyAll(without, withInclude))
        assertEquals(without, revertAll(without, withInclude))
    }

    @Test
    fun `a moved block carries its prefix lines and both ends exchange`() {
        val left = "A\n\n[[id]]\nMoved text.\n\nB\n"
        val right = "A\n\nB\n\n[[id]]\nMoved text.\n"
        val rendered = render(left, right)
        val moved = rendered.blocks.filter { it.movedFrom != null || it.movedTo != null }
        assertEquals(2, moved.size)
        val region = assertNotNull(moved.first { it.movedFrom != null }.edit)
        val lines = right.split('\n').subList(region.right.start, region.right.end)
        assertTrue(lines.contains("[[id]]") && lines.contains("Moved text."), lines.toString())
        assertEquals(left, revertAll(left, right))
        assertEquals(right, applyAll(left, right))
    }

    @Test
    fun `every AsciiDoc fixture applies to the right side and reverts to the left`() {
        val cases = cases()
        assertEquals(18, cases.size)
        for (case in cases) {
            val left = fixture(case, "old.adoc")
            val right = fixture(case, "new.adoc")
            assertEquals(right, applyAll(left, right), "$case apply")
            assertEquals(left, revertAll(left, right), "$case revert")
            assertEquals(tree(right), tree(applyAll(left, right)), "$case applied tree")
            assertEquals(tree(left), tree(revertAll(left, right)), "$case reverted tree")
            // A changed block whose lines differ must be editable; two fixtures also have a derived text-only
            // change (an attribute reference and a container type) that no single line edit can undo.
            val unresolved = render(left, right).changes.filter { it.edit == null && it.left != it.right }
            assertEquals(emptyList(), unresolved, "$case changed blocks without a region")
        }
    }

    @Test
    fun `63 adoc moved has five complete move pairs and round-trips`() {
        val left = fixture("63-adoc-moved", "old.adoc")
        val right = fixture("63-adoc-moved", "new.adoc")
        val rendered = render(left, right)
        val moved = rendered.blocks.filter { it.movedFrom != null || it.movedTo != null }
        assertEquals(10, moved.size)
        val pairs = moved.filter { it.movedTo != null }
        assertEquals(5, pairs.size)
        assertTrue(pairs.all { rendered.blocks[it.movedTo!!].movedFrom == it.idx })
        assertTrue(moved.all { it.edit != null })
        assertEquals(left, revertAll(left, right))
        assertEquals(right, applyAll(left, right))
    }

    private object TestProvider : AsciidoctorProvider {
        private val instance: Asciidoctor by lazy { Asciidoctor.Factory.create() }
        override fun asciidoctor(): Asciidoctor = instance
    }
}
