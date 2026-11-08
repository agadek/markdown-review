package pl.mdreview.engine.asciidoc

import org.asciidoctor.Asciidoctor
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffOptions
import pl.mdreview.engine.HtmlFragment
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * AsciiDoc diagrams and math (P4.7): `[mermaid]` blocks, inline `latexmath` / `stem` macros and `[latexmath]` /
 * `[stem]` block formulas.
 */
class AsciiDocDiagramsMathTest {

    private val source = AsciiDocBlockSource(TestProvider)

    private fun extract(text: String, options: DiffOptions = DiffOptions()): List<Block> =
        source.extract(text.trimIndent(), options)

    @Test
    fun `a mermaid listing becomes one block with its source lines and the style line in its range`() {
        val blocks = extract(
            """
            = T

            [mermaid,format=svg]
            ----
            flowchart LR
                A --> B
            ----

            [plantuml]
            ----
            @startuml
            A -> B
            @enduml
            ----
            """,
        )
        val mermaid = blocks.single { it.kind == BlockKind.MERMAID }
        assertEquals("flowchart LR\n    A --> B", mermaid.plainText)
        assertEquals("mermaid", mermaid.language)
        assertEquals(2 to 7, mermaid.source.startLine to mermaid.source.endLine)
        assertContains(mermaid.rawText, "[mermaid,format=svg]")
        assertContains(mermaid.rawText, "----")
        assertContains(mermaid.key, "mermaid")
        assertEquals(
            listOf(BlockKind.CODE_FENCE, BlockKind.CODE_LINE, BlockKind.CODE_LINE),
            mermaid.lines.map { it.kind },
        )
        assertEquals(listOf(2, 4, 5), mermaid.lines.map { it.source.startLine })
        assertEquals(listOf("mermaid", "flowchart LR", "    A --> B"), mermaid.lines.map { it.plainText })
        // Other asciidoctor-diagram styles stay code.
        assertTrue(blocks.none { it.kind == BlockKind.MERMAID && it.plainText.startsWith("@startuml") })
        assertEquals(
            listOf(BlockKind.CODE_FENCE, BlockKind.CODE_LINE, BlockKind.CODE_LINE, BlockKind.CODE_LINE),
            blocks.filter { it.source.startLine >= 8 }.map { it.kind },
        )
    }

    @Test
    fun `a mermaid literal block is drawn too`() {
        val mermaid = extract(
            """
            = T

            [mermaid]
            ....
            graph TD
                A --> B
            ....
            """,
        ).single { it.kind == BlockKind.MERMAID }
        assertEquals("graph TD\n    A --> B", mermaid.plainText)
        assertEquals(2 to 7, mermaid.source.startLine to mermaid.source.endLine)
    }

    @Test
    fun `with mermaid rendering off the block stays code`() {
        val text = """
            = T

            [mermaid]
            ----
            flowchart LR
                A --> B
            ----
            """
        val blocks = extract(text, DiffOptions(mermaidDiagrams = false))
        assertTrue(blocks.none { it.kind == BlockKind.MERMAID })
        assertEquals(BlockKind.CODE_FENCE, blocks[1].kind)
        assertEquals("mermaid", blocks[1].plainText)
        assertEquals(listOf("flowchart LR", "    A --> B"), blocks.drop(2).map { it.plainText })
    }

    @Test
    fun `ignored diagram attributes are part of the block key`() {
        val plain = extract("[mermaid]\n----\nflowchart LR\n    A --> B\n----\n").single { it.kind == BlockKind.MERMAID }
        val sized = extract("[mermaid,format=svg,width=200]\n----\nflowchart LR\n    A --> B\n----\n")
            .single { it.kind == BlockKind.MERMAID }
        assertEquals(plain.plainText, sized.plainText)
        assertNotEquals(plain.key, sized.key)
        assertContains(sized.key, "format=svg")
    }

    @Test
    fun `inline latex and stem become atoms while asciimath stays text`() {
        val paragraph = extract(
            """
            = T
            :stem: latexmath

            Area latexmath:[A = \pi r^2] and stem:[C = 2 \pi r] and asciimath:[a+b].
            """,
        ).single { it.kind == BlockKind.PARAGRAPH }
        assertEquals("Area A = \\pi r^2 and C = 2 \\pi r and \$a+b\$.", paragraph.plainText)
        assertEquals(2, paragraph.atoms.size)
        assertEquals("A = \\pi r^2", paragraph.plainText.substring(paragraph.atoms[0].first, paragraph.atoms[0].last + 1))
        assertEquals("C = 2 \\pi r", paragraph.plainText.substring(paragraph.atoms[1].first, paragraph.atoms[1].last + 1))
        val parts = (paragraph.content as BlockContent.Html).parts
        val spans = parts.filterIsInstance<HtmlFragment.Markup>().filter { it.tag == "span" && !it.closing }
        assertContains(spans.map { it.html }.joinToString(""), "class=\"mdr-math mdr-math-inline\"")
        assertContains(spans.map { it.html }.joinToString(""), "mdr-math-failed")
        assertContains(spans.map { it.html }.joinToString(""), "AsciiMath is not supported")
    }

    @Test
    fun `stem follows the document attribute and latexmath is always latex`() {
        val ascii = extract(
            """
            = T

            Inline stem:[a+b] and latexmath:[c+d].
            """,
        ).single { it.kind == BlockKind.PARAGRAPH }
        assertEquals("Inline \$a+b\$ and c+d.", ascii.plainText)
        assertEquals(listOf("c+d"), ascii.atoms.map { ascii.plainText.substring(it.first, it.last + 1) })

        val latex = extract(
            """
            = T
            :stem:

            Inline stem:[a+b] and latexmath:[c+d].
            """,
        ).single { it.kind == BlockKind.PARAGRAPH }
        assertEquals("Inline \$a+b\$ and c+d.", latex.plainText)
        assertEquals(listOf("c+d"), latex.atoms.map { latex.plainText.substring(it.first, it.last + 1) })
    }

    @Test
    fun `latex and stem blocks are one math block with their source lines`() {
        val blocks = extract(
            """
            = T
            :stem: latexmath

            [latexmath]
            ++++
            E = mc^2
            ++++

            [stem]
            ++++
            x = 1
            ++++

            [asciimath]
            ++++
            c = d
            ++++
            """,
        )
        val math = blocks.filter { it.kind == BlockKind.MATH }
        assertEquals(listOf("E = mc^2", "x = 1", "c = d"), math.map { it.plainText })
        assertEquals(listOf("latexmath", "latexmath", "asciimath"), math.map { it.language })
        assertEquals(listOf(3 to 7, 8 to 12, 13 to 17), math.map { it.source.startLine to it.source.endLine })
        assertEquals(listOf(5, 10, 15), math.map { it.lines.single().source.startLine })
        assertEquals(listOf(BlockKind.CODE_LINE), math.map { it.lines.single().kind }.distinct())
        math.forEach { assertContains(it.rawText, "++++") }
    }

    @Test
    fun `an asciimath block is marked as not drawn`() {
        val block = extract(
            """
            = T

            [asciimath]
            ++++
            sum_(i=1)^n i
            ++++
            """,
        ).single { it.kind == BlockKind.MATH }
        assertEquals("asciimath", block.language)
        assertEquals("sum_(i=1)^n i", block.plainText)
        assertFalse(block.atoms.isNotEmpty())
    }

    @Test
    fun `stem without the attribute is asciimath and an explicit latexmath block is latex`() {
        val blocks = extract(
            """
            = T

            [stem]
            ++++
            x = 1
            ++++

            [latexmath]
            ++++
            y = 2
            ++++
            """,
        ).filter { it.kind == BlockKind.MATH }
        assertEquals(listOf("asciimath", "latexmath"), blocks.map { it.language })
    }

    @Test
    fun `an inline formula in a table cell is an atom with the cell gap`() {
        val row = extract(
            """
            = T
            :stem: latexmath

            [cols="1,1"]
            |===
            | Area | latexmath:[A = \pi r^2]
            |===
            """,
        ).single { it.kind == BlockKind.TABLE_ROW }
        assertEquals("Area | A = \\pi r^2", row.plainText)
        val atom = row.atoms.single()
        assertEquals("A = \\pi r^2", row.plainText.substring(atom.first, atom.last + 1))
    }

    private object TestProvider : AsciidoctorProvider {
        private val instance: Asciidoctor by lazy { Asciidoctor.Factory.create() }
        override fun asciidoctor(): Asciidoctor = instance
    }
}
