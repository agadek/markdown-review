package pl.mdreview.engine.asciidoc

import org.asciidoctor.Asciidoctor
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * For every AsciiDoc fixture side, block ranges are in order, do not overlap, and cover every non-blank source
 * line outside delimiter and prefix lines exactly once.
 *
 * The one deliberate exception is a description list entry: ADR 0005 gives its term and description the same source
 * line, so the two rows share that line (and one edit region), by design.
 */
class AsciiDocLineRangesTest {

    private val source = AsciiDocBlockSource(TestProvider)

    @Test
    fun `ranges are ordered non-overlapping and cover every content line`() {
        val dir = File(javaClass.getResource("/fixtures")!!.toURI())
        val cases = dir.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name.matches(Regex("5[0-9]-.*")) || it.name.matches(Regex("6[0-4]-.*"))) }
            .sortedBy { it.name }
        assertEquals(15, cases.size)
        for (case in cases) {
            for (side in listOf("old", "new")) {
                val file = File(case, "$side.adoc")
                if (!file.isFile) continue
                checkSide("${case.name}/$side", file.readText())
            }
        }
    }

    private fun checkSide(name: String, text: String) {
        val lines = AsciiDocLines(text)
        val blocks = source.extract(text, DiffOptions())
        for (i in blocks.indices) {
            val block = blocks[i]
            assertTrue(block.source.startLine >= 0, "$name: ${block.kind} starts before the file")
            assertTrue(block.source.endLine > block.source.startLine, "$name: ${block.kind} has an empty range")
            assertTrue(block.source.endLine <= lines.lines.size, "$name: ${block.kind} ends after the file")
            if (i == 0) continue
            val previous = blocks[i - 1]
            assertTrue(block.source.startLine >= previous.source.startLine, "$name: block ${i} is out of order")
            if (!sharesDescriptionLine(previous, block)) {
                assertTrue(
                    block.source.startLine >= previous.source.endLine,
                    "$name: ${previous.kind} ${previous.source.startLine}..${previous.source.endLine} overlaps " +
                        "${block.kind} ${block.source.startLine}..${block.source.endLine}",
                )
            }
        }
        for (i in lines.lines.indices) {
            val line = lines.lines[i]
            if (line.isBlank() || i in lines.delimiterLines || i in lines.prefixLines) continue
            val covering = blocks.filter { i >= it.source.startLine && i < it.source.endLine }
            val expected = if (covering.size == 2 && sharesDescriptionLine(covering[0], covering[1])) 2 else 1
            assertEquals(
                expected,
                covering.size,
                "$name: line ${i + 1} (${line.trim().take(60)}) is covered by " +
                    covering.joinToString { "${it.kind} ${it.source.startLine + 1}..${it.source.endLine}" },
            )
        }
    }

    private fun sharesDescriptionLine(first: pl.mdreview.engine.Block, second: pl.mdreview.engine.Block): Boolean =
        first.source.startLine == second.source.startLine &&
            ((first.kind == BlockKind.DESCRIPTION_TERM && second.kind == BlockKind.LIST_ITEM) ||
                (first.kind == BlockKind.LIST_ITEM && second.kind == BlockKind.DESCRIPTION_TERM))

    private object TestProvider : AsciidoctorProvider {
        private val instance: Asciidoctor by lazy { Asciidoctor.Factory.create() }
        override fun asciidoctor(): Asciidoctor = instance
    }
}
