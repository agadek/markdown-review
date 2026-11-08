package pl.mdreview.engine

import org.asciidoctor.Asciidoctor
import pl.mdreview.api.BlockStatus
import pl.mdreview.api.DocumentFormat
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
import pl.mdreview.api.DiffOptions as ApiDiffOptions
import pl.mdreview.engine.asciidoc.AsciiDocBlockSource
import pl.mdreview.engine.asciidoc.AsciidoctorProvider
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.HtmlFragment
import pl.mdreview.render.CoreMarkdownDiffRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the AsciiDoc fixture pairs 50-64 through the AsciiDoc front end and pins the result their `EXPECTED.md`
 * describes. AsciiDoc fixture cases are documented in `src/test/resources/fixtures/README.md`.
 */
class AsciiDocFixturesTest {

    private val provider = TestProvider
    private val source = AsciiDocBlockSource(provider)
    private val engine = DiffEngine(DiffOptions(), source)

    private fun read(case: String, name: String): String {
        val url = javaClass.getResource("/fixtures/$case/$name") ?: error("Missing fixture $case/$name")
        return File(url.toURI()).readBytes().toString(Charsets.UTF_8)
    }

    private fun diff(case: String) = engine.diff(read(case, "old.adoc"), read(case, "new.adoc"))

    private fun added(block: DiffBlock): String =
        block.inline.filter { it.status == DiffStatus.ADDED }.joinToString("") { it.text }.trim()

    private fun removed(block: DiffBlock): String =
        block.inline.filter { it.status == DiffStatus.REMOVED }.joinToString("") { it.text }.trim()

    private fun change(case: String, plainText: String): DiffBlock =
        diff(case).changes.single { (it.right ?: it.left)!!.plainText == plainText }

    private fun kinds(blocks: List<DiffBlock>): List<BlockKind> = blocks.map { (it.right ?: it.left)!!.kind }

    @Test
    fun `50 paragraphs word diff and a formatting-only change`() {
        val d = diff("50-adoc-paragraph-edit")
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.MODIFIED, DiffStatus.MODIFIED, DiffStatus.MODIFIED, DiffStatus.EQUAL),
            d.blocks.map { it.status },
        )
        assertEquals(
            listOf(BlockKind.HEADING, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH),
            kinds(d.blocks),
        )
        val first = d.changes[0]
        assertContains(added(first), "directly")
        assertTrue(first.inline.any { it.status == DiffStatus.REMOVED && it.text == "1" })
        assertTrue(first.inline.any { it.status == DiffStatus.ADDED && it.text == "2" })
        assertTrue(d.changes[2].inline.all { it.status == DiffStatus.EQUAL })
        assertContains(d.changes[2].left!!.key, "*block menu*")
        assertContains(d.changes[2].right!!.key, "block menu")
    }

    @Test
    fun `51 section levels discrete headings and the document title`() {
        val d = diff("51-adoc-sections")
        assertEquals(3, d.changes.size)
        assertEquals(listOf("Installation", "Settings", "Release Notes"), d.changes.map { (it.right ?: it.left)!!.plainText })
        val installation = d.changes[0]
        assertEquals(3, installation.left!!.level)
        assertEquals(4, installation.right!!.level)
        assertTrue(installation.inline.all { it.status == DiffStatus.EQUAL })
        assertEquals("Configuration", removed(d.changes[1]))
        assertEquals("Settings", added(d.changes[1]))
        assertEquals(2, d.changes[2].left!!.level)
        assertEquals(3, d.changes[2].right!!.level)
        assertEquals(DiffStatus.EQUAL, d.blocks.first().status)
        assertEquals(1, d.blocks.first().right!!.level)
    }

    @Test
    fun `52 nested ordered checklist and description lists`() {
        val d = diff("52-adoc-lists")
        val changes = d.changes
        assertTrue(changes.any { it.status == DiffStatus.MODIFIED && it.right!!.plainText == "words and characters" && added(it) == "and characters" })
        assertTrue(changes.any { it.status == DiffStatus.ADDED && it.right!!.plainText == "Fourth step" })
        val update = changes.single { it.right?.plainText == "Update the changelog" }
        assertTrue(update.inline.all { it.status == DiffStatus.EQUAL })
        assertContains(update.left!!.key, "task=unchecked")
        assertContains(update.right!!.key, "task=checked")
        assertContains(added(changes.single { it.right?.plainText == "Tag the release on GitHub" }), "on GitHub")
        assertEquals(3, changes.single { it.right?.plainText == "words and characters" }.right!!.context.listDepth)
        assertEquals(3, changes.single { it.right?.plainText == "moves" }.right!!.context.listDepth)
        // One moved list item: same key, one removed and one added end.
        val moved = d.blocks.filter { it.movedFrom != null || it.movedTo != null }
        assertEquals(2, moved.size)
        assertTrue(moved.all { (it.right ?: it.left)!!.plainText == "HTML generator" })
        // Description: the term is equal, its description changed.
        val term = d.blocks.single { (it.right ?: it.left)!!.kind == BlockKind.DESCRIPTION_TERM && (it.right ?: it.left)!!.plainText == "EXPECTED.md" }
        assertEquals(DiffStatus.EQUAL, term.status)
        val description = changes.single { it.right?.plainText == "The expected block changes." }
        assertEquals("block", added(description))
        assertTrue(description.right!!.context.containers.any { it.kind == "description" })
        assertContains(added(changes.single { it.right?.plainText == "The block above is a section title." }), "title")
    }

    @Test
    fun `53 tables change added and removed rows and a cells`() {
        val d = diff("53-adoc-tables")
        assertEquals(
            listOf(DiffStatus.MODIFIED, DiffStatus.MODIFIED, DiffStatus.REMOVED, DiffStatus.ADDED),
            d.changes.map { it.status },
        )
        assertEquals(listOf(BlockKind.TABLE_ROW), kinds(d.changes).distinct())
        val pycharm = d.changes[0]
        assertEquals("PyCharm | 2025.1 | Community and Professional.", pycharm.left!!.plainText)
        assertEquals("PyCharm | 2025.2 | Community and Professional editions.", pycharm.right!!.plainText)
        assertContains(added(pycharm), "editions")
        assertEquals("Rider | 2025.1 | Unknown.", d.changes[2].left!!.plainText)
        assertEquals("GoLand | 2025.1 | Supported.", d.changes[3].right!!.plainText)
        val header = d.blocks.single { it.right?.key?.startsWith("th:") == true }
        assertEquals(DiffStatus.EQUAL, header.status)
        assertEquals("IDE | Version | Notes", header.right!!.plainText)
    }

    @Test
    fun `54 source blocks language change line changes and callouts`() {
        val d = diff("54-adoc-listing")
        assertEquals(
            listOf(BlockKind.CODE_LINE, BlockKind.CODE_LINE, BlockKind.CODE_FENCE, BlockKind.LIST_ITEM),
            kinds(d.changes),
        )
        assertContains(added(d.changes[0]), "ignoreWhitespace = true")
        assertEquals("    diff.blocks.forEach { println(it.status) }", d.changes[1].right!!.plainText)
        val fence = d.changes[2]
        assertEquals(null, fence.left!!.language)
        assertEquals("kotlin", fence.right!!.language)
        assertContains(fence.right!!.key, "language=kotlin")
        val callout = d.changes[3]
        assertEquals("and level", added(callout))
        assertTrue(callout.right!!.context.containers.any { it.kind == "callout" })
        assertEquals(1, callout.right!!.context.number)
        // The <1> marker is part of the code line.
        assertTrue(d.blocks.any { it.right?.plainText?.contains("// <1>") == true })
    }

    @Test
    fun `55 admonition paragraphs and blocks and a type change`() {
        val d = diff("55-adoc-admonitions")
        assertEquals(listOf(BlockKind.ALERT, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH), kinds(d.changes))
        val alert = d.changes[0]
        assertEquals("by default", removed(alert))
        assertContains(added(alert), "unless you enable them in the settings")
        assertEquals("NOTE", alert.right!!.context.containers.single().type)
        val firstInner = d.changes[1]
        assertEquals("WARNING", firstInner.left!!.context.containers.single().type)
        assertEquals("CAUTION", firstInner.right!!.context.containers.single().type)
        assertTrue(firstInner.inline.all { it.status == DiffStatus.EQUAL })
        assertTrue(d.changes[2].inline.all { it.status == DiffStatus.EQUAL })
        assertEquals(DiffStatus.EQUAL, d.blocks.single { it.right?.kind == BlockKind.ALERT && it.right!!.plainText.startsWith("Press F7") }.status)
    }

    @Test
    fun `56 example sidebar quote open and passthrough blocks`() {
        val d = diff("56-adoc-containers")
        assertEquals(
            listOf(BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH, BlockKind.PARAGRAPH),
            kinds(d.changes),
        )
        assertEquals(
            listOf(
                listOf("example"),
                listOf("sidebar"),
                listOf("quote"),
                listOf("open"),
                listOf("example", "sidebar"),
            ),
            d.changes.map { it.right!!.context.containers.map { c -> c.kind } },
        )
        assertEquals(", revised", added(d.changes[0]))
        assertEquals("with extra detail", added(d.changes[1]))
        assertEquals("whatever", removed(d.changes[2]))
        assertContains(d.changes[3].right!!.plainText, "bold")
        val boldMarkup = (d.changes[3].right!!.content as BlockContent.Html).parts
            .filterIsInstance<HtmlFragment.Markup>().map { it.html }
        assertTrue(boldMarkup.contains("<strong>"))
        assertEquals(", revised", added(d.changes[4]))
        // Titles, thematic break, page break and passthrough are equal.
        assertEquals(DiffStatus.EQUAL, d.blocks.single { it.right?.kind == BlockKind.BLOCK_TITLE && it.right!!.plainText == "Sidebar" }.status)
        val breaks = d.blocks.filter { it.right?.kind == BlockKind.THEMATIC_BREAK }
        assertEquals(listOf("'''", "<<<"), breaks.map { it.right!!.rawText })
        assertTrue(breaks.all { it.status == DiffStatus.EQUAL })
        val html = d.blocks.single { it.right?.kind == BlockKind.HTML }
        assertEquals("Raw HTML from a passthrough block.", html.right!!.plainText)
    }

    @Test
    fun `57 block titles add remove and change as their own rows`() {
        val d = diff("57-adoc-block-titles")
        assertEquals(
            listOf(DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.REMOVED, DiffStatus.MODIFIED),
            d.changes.map { it.status },
        )
        assertEquals(
            listOf(BlockKind.BLOCK_TITLE, BlockKind.BLOCK_TITLE, BlockKind.BLOCK_TITLE, BlockKind.CODE_LINE),
            kinds(d.changes),
        )
        assertEquals("Original", removed(d.changes[0]))
        assertEquals("New", added(d.changes[0]))
        assertEquals("Modern example", d.changes[1].right!!.plainText)
        assertEquals("Deprecated notice", d.changes[2].left!!.plainText)
        assertEquals("1", removed(d.changes[3]))
        assertEquals("2", added(d.changes[3]))
        val retained = d.blocks.single { it.right?.kind == BlockKind.BLOCK_TITLE && it.right!!.plainText == "Retained title" }
        assertEquals(DiffStatus.EQUAL, retained.status)
    }

    @Test
    fun `58 header and body attributes change the entries and the paragraphs that use them`() {
        val d = diff("58-adoc-attributes")
        assertEquals(
            listOf(BlockKind.ATTRIBUTE, BlockKind.PARAGRAPH, BlockKind.ATTRIBUTE),
            kinds(d.changes),
        )
        assertEquals(":product-version: 1.4", d.changes[0].right!!.plainText)
        assertEquals("3", removed(d.changes[0]))
        assertEquals("4", added(d.changes[0]))
        val paragraph = d.changes[1]
        assertEquals("3", removed(paragraph))
        assertEquals("4", added(paragraph))
        assertContains(paragraph.right!!.plainText, "Markdown Review 1.4")
        assertEquals(":status: stable", d.changes[2].right!!.plainText)
        assertEquals("beta", removed(d.changes[2]))
        assertEquals("stable", added(d.changes[2]))
    }

    @Test
    fun `59 comments are hidden rows unless they changed`() {
        val d = diff("59-adoc-comments")
        assertEquals(
            listOf(DiffStatus.MODIFIED, DiffStatus.ADDED, DiffStatus.MODIFIED),
            d.changes.map { it.status },
        )
        assertEquals(listOf(BlockKind.HTML_COMMENT, BlockKind.HTML_COMMENT, BlockKind.HTML_COMMENT), kinds(d.changes))
        assertEquals(" of the merged view", d.changes[0].inline.single { it.status == DiffStatus.ADDED }.text)
        assertEquals("// Added: explain the generated section.", d.changes[1].right!!.rawText)
        assertContains(removed(d.changes[2]), "Do not edit by hand")
        assertContains(added(d.changes[2]), "Review before editing")
        val unchanged = d.blocks.single { it.right?.kind == BlockKind.HTML_COMMENT && it.right!!.plainText == "This comment stays unchanged." }
        assertEquals(DiffStatus.EQUAL, unchanged.status)
    }

    @Test
    fun `60 include directives are not resolved`() {
        val d = diff("60-adoc-includes")
        assertEquals(
            listOf(DiffStatus.MODIFIED, DiffStatus.REMOVED, DiffStatus.ADDED),
            d.changes.map { it.status },
        )
        assertEquals(listOf(BlockKind.INCLUDE, BlockKind.INCLUDE, BlockKind.INCLUDE), kinds(d.changes))
        assertEquals("chapters/introduction.adoc", d.changes[0].right!!.target)
        assertEquals("intro", removed(d.changes[0]))
        assertEquals("introduction", added(d.changes[0]))
        assertEquals("chapters/installation.adoc", d.changes[1].left!!.target)
        assertEquals("chapters/appendix.adoc", d.changes[2].right!!.target)
        assertEquals(DiffStatus.EQUAL, d.blocks.single { it.right?.plainText == "The rest of the book." }.status)
    }

    @Test
    fun `61 links xrefs and images change text or target`() {
        val d = diff("61-adoc-links-images")
        assertEquals(
            listOf(
                BlockKind.PARAGRAPH,
                BlockKind.PARAGRAPH,
                BlockKind.PARAGRAPH,
                BlockKind.PARAGRAPH,
                BlockKind.PARAGRAPH,
                BlockKind.IMAGE,
            ),
            kinds(d.changes),
        )
        assertEquals("user", added(d.changes[0]))
        for (i in 1..4) assertTrue(d.changes[i].inline.all { it.status == DiffStatus.EQUAL }, "change $i")
        assertEquals("images/screenshot.png", d.changes[5].left!!.target)
        assertEquals("images/screenshot-dark.png", d.changes[5].right!!.target)
        assertEquals("Screenshot of the diff window", d.changes[5].right!!.plainText)
        // The anchor line above the section is a prefix, not a row; the heading stays equal.
        assertTrue(d.blocks.none { it.left?.rawText?.contains("[[settings]]") == true || it.right?.rawText?.contains("[[configuration]]") == true })
        assertEquals(DiffStatus.EQUAL, d.blocks.single { it.right?.kind == BlockKind.HEADING && it.right!!.plainText == "Settings" }.status)
        assertContains(d.changes[2].right!!.rawText, "xref:setup.adoc")
        assertContains(d.changes[3].right!!.rawText, "<<configuration>>")
    }

    @Test
    fun `62 conditionals are hidden rows and their content is evaluated`() {
        val d = diff("62-adoc-conditionals")
        assertEquals(listOf(BlockKind.HTML_COMMENT, BlockKind.PARAGRAPH), kinds(d.changes))
        assertEquals("ifdef::product-release[]", d.changes[0].right!!.plainText)
        assertEquals("version", removed(d.changes[0]))
        assertEquals("release", added(d.changes[0]))
        assertEquals("on every platform", added(d.changes[1]))
        assertEquals("This documentation covers 1.4.", d.blocks.single { it.left?.plainText == "This documentation covers 1.4." }.right!!.plainText)
        assertEquals(DiffStatus.EQUAL, d.blocks.single { it.right?.plainText == "The rest of the page is always shown." }.status)
    }

    @Test
    fun `63 a moved section is linked as moves without a word diff`() {
        val d = diff("63-adoc-moved")
        assertEquals(10, d.changes.size)
        assertTrue(d.changes.all { it.status == DiffStatus.REMOVED || it.status == DiffStatus.ADDED })
        assertTrue(d.changes.all { it.movedFrom != null || it.movedTo != null })
        assertTrue(d.changes.all { it.inline.isEmpty() })
        assertEquals(
            listOf("Configuration", "Configuration is stored in the project settings.", "Project settings", "IDE settings", "Shared profiles"),
            d.changes.filter { it.status == DiffStatus.REMOVED }.map { it.left!!.plainText },
        )
        assertEquals(
            listOf("Configuration", "Configuration is stored in the project settings.", "Project settings", "IDE settings", "Shared profiles"),
            d.changes.filter { it.status == DiffStatus.ADDED }.map { it.right!!.plainText },
        )
    }

    @Test
    fun `64 large document has the expected changes`() {
        val start = System.nanoTime()
        val d = diff("64-adoc-large")
        val ms = (System.nanoTime() - start) / 1_000_000
        val modified = d.changes.filter { it.status == DiffStatus.MODIFIED }
        val added = d.changes.filter { it.status == DiffStatus.ADDED }
        val removed = d.changes.filter { it.status == DiffStatus.REMOVED }
        assertEquals(20, d.changes.size)
        assertEquals(8, modified.size)
        assertTrue(modified.all { it.right!!.kind == BlockKind.PARAGRAPH && added(it) == "(changed)" })
        assertEquals(2, added.size)
        assertTrue(added.all { it.right!!.kind == BlockKind.PARAGRAPH && it.right!!.plainText == "A new paragraph was added in the large AsciiDoc file." })
        assertEquals(10, removed.size)
        assertEquals(
            mapOf(BlockKind.HEADING to 1, BlockKind.PARAGRAPH to 1, BlockKind.LIST_ITEM to 4, BlockKind.TABLE_ROW to 4),
            removed.groupingBy { it.left!!.kind }.eachCount(),
        )
        assertEquals("Section 275", removed.first { it.left!!.kind == BlockKind.HEADING }.left!!.plainText)
        assertTrue(ms < 10_000, "AsciiDoc diff of the large fixture took $ms ms")
    }

    @Test
    fun `65 adoc mermaid blocks become one diagram each like Markdown`() {
        val d = diff("65-adoc-mermaid")
        val diagrams = d.blocks.filter { (it.right ?: it.left)!!.kind == BlockKind.MERMAID }
            .map { it.status to (it.right ?: it.left)!!.plainText.lines().first() }
        assertEquals(
            listOf(
                DiffStatus.EQUAL to "sequenceDiagram",
                DiffStatus.MODIFIED to "flowchart TD",
                DiffStatus.REMOVED to "pie title Languages",
                DiffStatus.ADDED to "stateDiagram-v2",
                DiffStatus.MODIFIED to "flowchart LR",
                DiffStatus.MODIFIED to "classDiagram",
            ),
            diagrams,
        )
        val changed = diagrams[1].let { d.blocks.single { b -> (b.right ?: b.left)!!.plainText.startsWith("flowchart TD") } }
        assertContains(changed.inline.filter { it.status == DiffStatus.ADDED }.joinToString("") { it.text }, "Fix")
        // The ignored asciidoctor-diagram attribute is a source-only change: both drawings, an empty word diff.
        val sized = d.blocks.single { (it.right ?: it.left)!!.plainText.startsWith("classDiagram") }
        assertEquals(DiffStatus.MODIFIED, sized.status)
        assertTrue(sized.inline.all { it.status == DiffStatus.EQUAL })
        assertEquals(changed, d.changes.single { (it.right ?: it.left)!!.plainText.startsWith("flowchart TD") })
    }

    @Test
    fun `65 adoc mermaid with rendering off is diffed as code`() {
        val d = DiffEngine(DiffOptions(mermaidDiagrams = false), source)
            .diff(read("65-adoc-mermaid", "old.adoc"), read("65-adoc-mermaid", "new.adoc"))
        assertTrue(d.blocks.none { (it.right ?: it.left)!!.kind == BlockKind.MERMAID })
        assertTrue(d.changes.any { it.status == DiffStatus.MODIFIED && (it.right ?: it.left)!!.kind == BlockKind.CODE_FENCE })
        assertTrue(d.changes.any { it.status == DiffStatus.MODIFIED && (it.right ?: it.left)!!.kind == BlockKind.CODE_LINE })
        // The `[mermaid,format=svg]` attribute line is the fence of its code block, so the attribute change is kept.
        val fence = d.changes.filter { (it.right ?: it.left)!!.kind == BlockKind.CODE_FENCE && it.status == DiffStatus.MODIFIED }
        assertTrue(fence.any { it.left?.rawText?.contains("format=svg") == true }, fence.toString())
    }

    @Test
    fun `66 adoc math formulas are compared as units and AsciiMath uses its source`() {
        val d = diff("66-adoc-math")
        assertEquals(
            listOf(
                "MODIFIED PARAGRAPH",
                "MODIFIED MATH",
                "REMOVED MATH",
                "MODIFIED PARAGRAPH",
                "ADDED PARAGRAPH",
                "ADDED MATH",
                "ADDED PARAGRAPH",
            ),
            d.changes.map { "${it.status} ${(it.right ?: it.left)!!.kind.name}" },
        )
        val formulas = d.blocks.filter { (it.right ?: it.left)!!.kind == BlockKind.MATH }
        assertEquals(
            listOf(
                DiffStatus.EQUAL to "E = mc^2",
                DiffStatus.MODIFIED to "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2 a}",
                DiffStatus.REMOVED to "a^2 + b^2 = c^2",
                DiffStatus.EQUAL to "x = (-b +- sqrt(b^2 - 4ac)) / (2a)",
                DiffStatus.ADDED to "e^{i\\pi} + 1 = 0",
            ),
            formulas.map { it.status to (it.right ?: it.left)!!.plainText },
        )
        // The AsciiMath block is a MATH block compared as its source and converted for KaTeX.
        assertEquals("asciimath", formulas[3].right!!.language)
        assertNotNull(formulas[3].right!!.mathTex)
        val ascii = d.changes[3]
        assertEquals(
            listOf("sum_(i=1)^n i", "a/b"),
            ascii.left!!.atoms.map { ascii.left!!.plainText.substring(it.first, it.last + 1) },
        )
        assertEquals(
            listOf("sum_(i=1)^n i", "frac(a)(b)"),
            ascii.right!!.atoms.map { ascii.right!!.plainText.substring(it.first, it.last + 1) },
        )
        assertEquals(listOf("a/b"), ascii.inline.filter { it.status == DiffStatus.REMOVED }.map { it.text })
        assertEquals(listOf("frac(a)(b)"), ascii.inline.filter { it.status == DiffStatus.ADDED }.map { it.text })
        // Inline formulas are atoms: the word diff keeps each whole formula.
        val circle = d.changes[0]
        assertEquals(listOf("C = 2 \\pi r"), circle.inline.filter { it.status == DiffStatus.REMOVED }.map { it.text })
        assertEquals(listOf("C = \\tau r"), circle.inline.filter { it.status == DiffStatus.ADDED }.map { it.text })
        assertEquals(2, circle.right!!.atoms.size)
        assertEquals(
            listOf("A = \\pi r^2", "C = \\tau r"),
            circle.right!!.atoms.map { circle.right!!.plainText.substring(it.first, it.last + 1) },
        )
        // The unfinished formula is still one inline formula node; drawing fails in the page.
        val unfinished = d.changes.last()
        assertEquals("An unfinished formula: \\frac{1}{.", unfinished.right!!.plainText)
        assertEquals(1, unfinished.right!!.atoms.size)
    }

    @Test
    fun `67 adoc asciimath is converted to latex and remains keyed by its source`() {
        val d = diff("67-adoc-asciimath")
        assertEquals(
            listOf("MODIFIED PARAGRAPH", "MODIFIED MATH"),
            d.changes.map { "${it.status} ${(it.right ?: it.left)!!.kind.name}" },
        )
        val paragraph = d.changes[0]
        assertEquals(
            listOf("frac(a)(b)", "sqrt(x)", "x^2"),
            paragraph.right!!.atoms.map { paragraph.right!!.plainText.substring(it.first, it.last + 1) },
        )
        assertEquals(listOf("a/b"), paragraph.inline.filter { it.status == DiffStatus.REMOVED }.map { it.text })
        assertEquals(listOf("frac(a)(b)"), paragraph.inline.filter { it.status == DiffStatus.ADDED }.map { it.text })

        val matrices = d.blocks.mapNotNull { it.right ?: it.left }.filter { it.kind == BlockKind.MATH }
        assertEquals(3, matrices.size)
        assertTrue(matrices.all { it.language == "asciimath" })
        assertTrue(matrices.all { it.mathTex != null })
        assertContains(matrices[0].mathTex!!, "\\begin{bmatrix}")
        assertContains(matrices[2].mathTex!!, "\\begin{pmatrix}")
        val rejected = d.blocks.mapNotNull { it.right ?: it.left }.single { it.plainText.contains("a + (b") }
        assertTrue(rejected.atoms.isEmpty())
    }

    @Test
    fun `65 and 66 render the Markdown diagram and formula DOM contract`() {
        val renderer = CoreMarkdownDiffRenderer(provider)
        for (mode in ViewMode.entries) {
            val mermaid = renderer.render(
                RenderRequest(
                    read("65-adoc-mermaid", "old.adoc"),
                    read("65-adoc-mermaid", "new.adoc"),
                    mode,
                    ApiDiffOptions(collapseUnchanged = false),
                    format = DocumentFormat.ASCIIDOC,
                ),
            )
            assertContains(mermaid.bodyHtml, "mdr-block", message = mode.name)
            assertContains(mermaid.bodyHtml, "mdr-mermaid", message = mode.name)
            assertContains(mermaid.bodyHtml, "pre class=\"mdr-mermaid-code\"", message = mode.name)
            assertContains(mermaid.bodyHtml, "mdr-mermaid-row", message = mode.name)
            assertEquals(3, Regex("<details class=\"mdr-mermaid-source\">").findAll(mermaid.bodyHtml).count(), mode.name)
            assertContains(mermaid.bodyHtml, "Show source changes", message = mode.name)
            assertEquals(mermaid.blocks.size, Regex("data-idx=").findAll(mermaid.bodyHtml).count(), mode.name)
            if (mode == ViewMode.MERGED) {
                assertContains(mermaid.bodyHtml, "mdr-mermaid-pair", message = mode.name)
                assertContains(mermaid.bodyHtml, "mdr-mermaid-old", message = mode.name)
            }

            val math = renderer.render(
                RenderRequest(
                    read("66-adoc-math", "old.adoc"),
                    read("66-adoc-math", "new.adoc"),
                    mode,
                    ApiDiffOptions(collapseUnchanged = false),
                    format = DocumentFormat.ASCIIDOC,
                ),
            )
            assertContains(math.bodyHtml, "class=\"mdr-math mdr-math-inline\"", message = mode.name)
            assertContains(math.bodyHtml, "class=\"mdr-math mdr-math-display\"", message = mode.name)
            assertContains(math.bodyHtml, "mdr-math-row", message = mode.name)
            assertContains(math.bodyHtml, "<details class=\"mdr-math-source\">", message = mode.name)
            assertContains(math.bodyHtml, "data-source=\"sum_(i=1)^n i\"", message = mode.name)
            assertContains(math.bodyHtml, "data-tex=\"\\sum_{i = 1}^{n} i\"", message = mode.name)
            assertFalse(math.bodyHtml.contains("AsciiMath is not supported"), mode.name)
            assertContains(math.bodyHtml, "\\frac{1}{", message = mode.name)
            assertContains(math.bodyHtml, "e^{i\\pi} + 1 = 0", message = mode.name)
            assertEquals(math.blocks.size, Regex("data-idx=").findAll(math.bodyHtml).count(), mode.name)
            if (mode == ViewMode.MERGED) {
                assertContains(math.bodyHtml, "mdr-math-pair", message = mode.name)
                assertContains(math.bodyHtml, "mdr-math-old", message = mode.name)
            }
        }
    }

    @Test
    fun `65 mermaid rendering off through the renderer has no diagram elements`() {
        val rendered = CoreMarkdownDiffRenderer(provider).render(
            RenderRequest(
                read("65-adoc-mermaid", "old.adoc"),
                read("65-adoc-mermaid", "new.adoc"),
                ViewMode.SIDE_BY_SIDE,
                ApiDiffOptions(renderMermaid = false),
                format = DocumentFormat.ASCIIDOC,
            ),
        )
        assertFalse(rendered.bodyHtml.contains("mdr-mermaid"))
        assertTrue(rendered.bodyHtml.contains("mdr-fence"))
        assertTrue(rendered.changes.any { it.status == BlockStatus.MODIFIED })
    }

    @Test
    fun `the renderer uses the provider for AsciiDoc and keeps the fallback without one`() {
        val request = RenderRequest(
            read("50-adoc-paragraph-edit", "old.adoc"),
            read("50-adoc-paragraph-edit", "new.adoc"),
            ViewMode.SIDE_BY_SIDE,
            format = DocumentFormat.ASCIIDOC,
        )
        val rendered = CoreMarkdownDiffRenderer(provider).render(request)
        assertEquals(
            diff("50-adoc-paragraph-edit").blocks.map { it.status.name },
            rendered.blocks.map { it.status.name },
        )
        assertTrue(rendered.blocks.any { it.status == BlockStatus.MODIFIED })
        assertFalse(rendered.bodyHtml.contains("= Installation Guide"))
    }

    @Test
    fun `every fixture renders with the AsciiDoc renderer`() {
        val renderer = CoreMarkdownDiffRenderer(provider)
        val cases = (50..66).map { n ->
            File(javaClass.getResource("/fixtures")!!.toURI()).listFiles()!!.first { it.name.startsWith("$n-") }.name
        }
        for (case in cases) {
            for (mode in ViewMode.entries) {
                val rendered = renderer.render(
                    RenderRequest(read(case, "old.adoc"), read(case, "new.adoc"), mode, format = DocumentFormat.ASCIIDOC),
                )
                assertEquals(
                    diff(case).blocks.map { it.status.name },
                    rendered.blocks.map { it.status.name },
                    "$case ${mode.name}",
                )
            }
        }
        // The AsciiDoc-only markup reached the renderer.
        val containers = renderer.render(
            RenderRequest(
                read("56-adoc-containers", "old.adoc"),
                read("56-adoc-containers", "new.adoc"),
                ViewMode.SIDE_BY_SIDE,
                format = DocumentFormat.ASCIIDOC,
            ),
        ).bodyHtml
        for (kind in listOf("example", "sidebar", "quote", "open")) {
            assertContains(containers, "mdr-container-$kind")
        }
        assertContains(containers, "mdr-page-break")
        val listing = renderer.render(
            RenderRequest(
                read("54-adoc-listing", "old.adoc"),
                read("54-adoc-listing", "new.adoc"),
                ViewMode.SIDE_BY_SIDE,
                format = DocumentFormat.ASCIIDOC,
            ),
        ).bodyHtml
        assertContains(listing, "data-change-note=\"source \u2192 kotlin\"")
        assertContains(listing, "mdr-callout")
    }

    private object TestProvider : AsciidoctorProvider {
        private val instance: Asciidoctor by lazy { Asciidoctor.Factory.create() }
        override fun asciidoctor(): Asciidoctor = instance
    }
}
