package pl.mdreview.render

import pl.mdreview.api.EditRegion
import pl.mdreview.api.LineRange
import pl.mdreview.engine.Block
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.MarkdownDiff

/**
 * Computes [EditRegion]s, the lines per-block revert and apply exchange between the sides (FR-12).
 *
 * The format-specific decisions live in [RegionRules]: Markdown keeps its blockquotes, fences and front matter
 * rules; AsciiDoc adds prefix lines, delimited groups and list continuations (P4.5).
 */
internal class EditRegions(
    diff: MarkdownDiff,
    left: String,
    right: String,
    private val rules: RegionRules = MarkdownRegionRules,
) {

    private val blocks = diff.blocks
    internal val leftSide = Side(blocks.map { it.left }, left)
    internal val rightSide = Side(blocks.map { it.right }, right)

    /** One region per diff block; `null` for blocks whose lines cannot change. */
    fun compute(): List<EditRegion?> = blocks.indices.map { k ->
        val rule = if (blocks[k].status == DiffStatus.EQUAL) rules::equalRegion else rules::region
        rule(k, blocks, leftSide, rightSide)?.takeIf(::changesText)
    }

    /**
     * False when both sides already hold the same lines, as for a block that changed only through a link
     * reference definition elsewhere in the file: replacing them would do nothing.
     */
    private fun changesText(region: EditRegion) = leftSide.text(region.left) != rightSide.text(region.right)

    /** The blocks of one side, indexed like the diff, and that side's lines. */
    internal class Side(private val blocks: List<Block?>, text: String) {
        val lines: List<String> = text.split('\n')

        fun block(i: Int): Block? = blocks[i]

        fun text(range: LineRange): List<String> = lines.subList(range.start, range.end)

        fun line(index: Int): String = lines.getOrElse(index) { "" }

        val lineCount: Int get() = lines.size

        fun previous(i: Int): Int? = (i - 1 downTo 0).firstOrNull { blocks[it] != null }

        fun next(i: Int): Int? = (i + 1 until blocks.size).firstOrNull { blocks[it] != null }

        /** From the end of the block before diff index [first] to the start of the block after [last]. */
        fun span(first: Int, last: Int, inside: Boolean): LineRange {
            val end = next(last)?.let { blocks[it]!!.source.startLine } ?: lines.size
            val start = previous(first)?.let { p ->
                val prev = blocks[p]!!
                if (inside && prev.kind in CONTAINER_LINES) prev.source.endLine else extendedEnd(p)
            } ?: 0
            return LineRange(minOf(start, end), end)
        }

        /** End of block [i] with the uncovered, non-blank lines that directly follow it. */
        fun extendedEnd(i: Int): Int {
            val limit = next(i)?.let { blocks[it]!!.source.startLine } ?: lines.size
            var end = blocks[i]!!.source.endLine
            while (end < limit && !isBlank(lines[end])) end++
            return end
        }

        /** Blank, or only quote markers: the line separates blocks. */
        private fun isBlank(line: String) = line.all { it.isWhitespace() || it == '>' }
    }

    private companion object {
        val CONTAINER_LINES = setOf(pl.mdreview.engine.BlockKind.CODE_LINE, pl.mdreview.engine.BlockKind.FRONT_MATTER, pl.mdreview.engine.BlockKind.CODE_FENCE)
    }
}
