package pl.mdreview.render

import pl.mdreview.api.EditRegion
import pl.mdreview.api.LineRange
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffBlock
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.MarkdownDiff

/**
 * Computes [EditRegion]s, the lines per-block revert and apply exchange between the sides (FR-12).
 *
 * A modified block exchanges its own lines. An added or removed block is replaced together with its
 * surroundings: on each side, the lines from the end of the previous block on that side to the start of the
 * next one. Copying that span from one side to the other reproduces the blank lines around the block exactly,
 * so reverting every change one by one gives back the other side's text.
 *
 * Lines no block covers that follow a block directly (a closing code fence, a table's delimiter row, the end
 * of front matter, link reference definitions) belong to that block and stay with it, except inside a code
 * block, where a line added or removed at the end goes before the closing fence.
 */
internal class EditRegions(diff: MarkdownDiff, left: String, right: String) {

    private val blocks = diff.blocks
    private val leftSide = Side(blocks.map { it.left }, left)
    private val rightSide = Side(blocks.map { it.right }, right)

    /** One region per diff block; `null` for equal blocks and for changes no line edit can undo. */
    fun compute(): List<EditRegion?> = blocks.indices.map { k -> region(k)?.takeIf(::changesText) }

    /**
     * False when both sides already hold the same lines, as for a block that changed only through a link
     * reference definition elsewhere in the file: replacing them would do nothing.
     */
    private fun changesText(region: EditRegion) = leftSide.text(region.left) != rightSide.text(region.right)

    private fun region(k: Int): EditRegion? {
        val block = blocks[k]
        return when (block.status) {
            DiffStatus.EQUAL -> null
            // With the lines that belong to the block, such as a setext underline or a table's delimiter row.
            DiffStatus.MODIFIED -> EditRegion(
                LineRange(block.left!!.source.startLine, leftSide.extendedEnd(k)),
                LineRange(block.right!!.source.startLine, rightSide.extendedEnd(k)),
            )
            DiffStatus.REMOVED, DiffStatus.ADDED -> {
                val own = if (block.status == DiffStatus.REMOVED) block.left!! else block.right!!
                val group = group(k)
                val inside = group.first == group.last && own.kind in CODE_LINES
                EditRegion(
                    leftSide.span(group.first, group.last, inside),
                    rightSide.span(group.first, group.last, inside),
                )
            }
        }
    }

    /**
     * The blocks that revert together with block [k]: a fenced code block or front matter, or a table, that was
     * added or removed as a whole. Otherwise just [k].
     */
    private fun group(k: Int): IntRange {
        val block = blocks[k]
        val side = if (block.status == DiffStatus.REMOVED) leftSide else rightSide
        val first = (k downTo 0).firstOrNull { i -> blocks[i].status != block.status || isGroupStart(side.block(i)) }
            ?.takeIf { blocks[it].status == block.status && isGroupStart(side.block(it)) }
            ?: return k..k
        val start = side.block(first)!!
        fun member(i: Int) = blocks[i].status == block.status && isMember(start, side.block(i))
        var last = first
        while (last + 1 < blocks.size && member(last + 1)) last++
        if (k > last) return k..k
        // Only a whole container is a group: the next block on this side must not continue it.
        val next = side.next(last)?.let(side::block)
        if (next != null && isMember(start, next)) return k..k
        return first..last
    }

    private fun isGroupStart(block: Block?) =
        block != null && (block.kind == BlockKind.CODE_FENCE || (block.kind == BlockKind.TABLE_ROW && block.key.startsWith("th:")))

    private fun isMember(start: Block, block: Block?): Boolean = when {
        block == null -> false
        start.kind == BlockKind.CODE_FENCE -> block.kind in CODE_LINES
        else -> block.kind == BlockKind.TABLE_ROW && block.key.startsWith("td:")
    }

    /** The blocks of one side, indexed like the diff, and that side's lines. */
    private class Side(private val blocks: List<Block?>, text: String) {
        private val lines = text.split('\n')

        fun block(i: Int): Block? = blocks[i]

        fun text(range: LineRange): List<String> = lines.subList(range.start, range.end)

        private fun previous(i: Int): Int? = (i - 1 downTo 0).firstOrNull { blocks[it] != null }

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
        val CODE_LINES = setOf(BlockKind.CODE_LINE, BlockKind.FRONT_MATTER)
        val CONTAINER_LINES = setOf(BlockKind.CODE_LINE, BlockKind.FRONT_MATTER, BlockKind.CODE_FENCE)
    }
}
