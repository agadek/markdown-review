package pl.mdreview.render

import pl.mdreview.api.EditRegion
import pl.mdreview.api.LineRange
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffBlock
import pl.mdreview.engine.DiffStatus

/**
 * The format-specific edit-region rules. A rule decides, for one diff block, which lines the two sides exchange
 * when the block is reverted or applied. [MarkdownRegionRules] keeps the Markdown behavior; AsciiDoc rules add
 * prefix lines, delimiters and continuation groups (P4.5).
 */
internal interface RegionRules {
    fun region(
        k: Int,
        blocks: List<DiffBlock>,
        left: EditRegions.Side,
        right: EditRegions.Side,
    ): EditRegion?

    /**
     * A region for a block the diff sees as equal but whose prefix lines differ, such as an AsciiDoc anchor
     * above an unchanged section title. Markdown has no such lines.
     */
    fun equalRegion(
        k: Int,
        blocks: List<DiffBlock>,
        left: EditRegions.Side,
        right: EditRegions.Side,
    ): EditRegion? = null
}

/**
 * Markdown's rules: a modified block exchanges its own lines plus the uncovered lines that follow it directly (a
 * setext underline, a table's delimiter row). An added or removed block exchanges, on each side, everything from
 * the end of the previous block to the start of the next one. A fenced code block, front matter or table added or
 * removed as a whole is one region for all its blocks, closing fence or delimiter row included.
 */
internal object MarkdownRegionRules : RegionRules {

    override fun region(
        k: Int,
        blocks: List<DiffBlock>,
        left: EditRegions.Side,
        right: EditRegions.Side,
    ): EditRegion? {
        val block = blocks[k]
        return when (block.status) {
            DiffStatus.EQUAL -> null
            // With the lines that belong to the block, such as a setext underline or a table's delimiter row.
            DiffStatus.MODIFIED -> EditRegion(
                LineRange(block.left!!.source.startLine, left.extendedEnd(k)),
                LineRange(block.right!!.source.startLine, right.extendedEnd(k)),
            )
            DiffStatus.REMOVED, DiffStatus.ADDED -> {
                val own = if (block.status == DiffStatus.REMOVED) block.left!! else block.right!!
                val group = group(k, blocks, if (block.status == DiffStatus.REMOVED) left else right)
                val inside = group.first == group.last && own.kind in CODE_LINES
                EditRegion(
                    left.span(group.first, group.last, inside),
                    right.span(group.first, group.last, inside),
                )
            }
        }
    }

    /**
     * The blocks that revert together with block [k]: a fenced code block or front matter, or a table, that was
     * added or removed as a whole. Otherwise just [k].
     */
    private fun group(k: Int, blocks: List<DiffBlock>, side: EditRegions.Side): IntRange {
        val block = blocks[k]
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

    private val CODE_LINES = setOf(BlockKind.CODE_LINE, BlockKind.FRONT_MATTER)
}
