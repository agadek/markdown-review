package pl.mdreview.render

import pl.mdreview.api.EditRegion
import pl.mdreview.api.LineRange
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockGroup
import pl.mdreview.engine.BlockGroupKind
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffBlock
import pl.mdreview.engine.DiffStatus

/**
 * AsciiDoc's edit regions (P4.5, FR-20).
 *
 * A modified block exchanges its own lines plus the metadata directly above it: an attribute line, an anchor, a
 * block title or a `+`. A delimited code block, table, container or header, or a list item with attached blocks,
 * added or removed as a whole is one region with its delimiters. A change inside an existing group stays inside
 * its opening and closing delimiter, so it never makes the document unbalanced. A block title exchanges its own
 * line only; an added or removed one is inserted where the block it titles starts on the other side.
 */
internal object AsciiDocRegionRules : RegionRules {

    override fun region(
        k: Int,
        blocks: List<DiffBlock>,
        left: EditRegions.Side,
        right: EditRegions.Side,
    ): EditRegion? {
        val block = blocks[k]
        return when (block.status) {
            DiffStatus.EQUAL -> null
            DiffStatus.MODIFIED -> modified(k, blocks, left, right)
            DiffStatus.REMOVED -> addedOrRemoved(k, blocks, left, right, removed = true)
            DiffStatus.ADDED -> addedOrRemoved(k, blocks, left, right, removed = false)
        }
    }

    /**
     * An anchor or attribute above a block whose content did not change is still part of the block's source:
     * offer the prefix lines so a changed anchor can be reverted against the unchanged section.
     */
    override fun equalRegion(
        k: Int,
        blocks: List<DiffBlock>,
        left: EditRegions.Side,
        right: EditRegions.Side,
    ): EditRegion? {
        val block = blocks[k]
        val leftBlock = block.left ?: return null
        val rightBlock = block.right ?: return null
        val leftStart = equalPrefixStart(leftBlock, k, blocks, left)
        val rightStart = equalPrefixStart(rightBlock, k, blocks, right)
        if (leftStart == leftBlock.source.startLine && rightStart == rightBlock.source.startLine) return null
        return EditRegion(
            LineRange(leftStart, leftBlock.source.endLine),
            LineRange(rightStart, rightBlock.source.endLine),
        )
    }

    private fun equalPrefixStart(block: Block, k: Int, blocks: List<DiffBlock>, side: EditRegions.Side): Int {
        val changed = changedLines(k, blocks, side)
        return block.prefixLines.filter { it !in changed }.minOrNull() ?: block.source.startLine
    }

    private fun modified(
        k: Int,
        blocks: List<DiffBlock>,
        left: EditRegions.Side,
        right: EditRegions.Side,
    ): EditRegion {
        val block = blocks[k]
        return EditRegion(
            LineRange(editStart(block.left!!, k, blocks, left, block.right, right), block.left!!.source.endLine),
            LineRange(editStart(block.right!!, k, blocks, right, block.left, left), block.right!!.source.endLine),
        )
    }

    private fun addedOrRemoved(
        k: Int,
        blocks: List<DiffBlock>,
        left: EditRegions.Side,
        right: EditRegions.Side,
        removed: Boolean,
    ): EditRegion {
        val block = blocks[k]
        val side = if (removed) left else right
        val other = if (removed) right else left
        val group = wholeGroup(k, blocks, side)
        if (group != null) return groupRegion(k, blocks, side, other, group, removed)

        val own = if (removed) block.left!! else block.right!!
        if (own.kind == BlockKind.BLOCK_TITLE) return titleRegion(k, blocks, side, other, own)
        if (own.kind == BlockKind.TABLE_ROW) {
            headerOptionRegion(k, blocks, side, own, removed)?.let { return it }
        }
        if (own.kind == BlockKind.CODE_LINE) {
            val code = own.editGroups.lastOrNull { it.kind == BlockGroupKind.CODE }
            return codeLineRegion(k, blocks, side, other, code, removed)
        }

        val bounds = enclosingBounds(own, selected = null)
        val ownRange = clamp(span(k, k, side, own, inside = false, blocks = blocks), bounds)
        val otherBounds = otherSideBounds(k, blocks, other, own, selected = null)
        val gap = clamp(span(k, k, other, own, inside = false, blocks = blocks), otherBounds)
        return if (removed) EditRegion(ownRange, gap) else EditRegion(gap, ownRange)
    }

    /**
     * The header row of a table whose `[options="header"]` changed is a row added (or removed) when Asciidoctor
     * reclassifies it. Its region also covers the table's attribute line and opening delimiter, so applying it
     * changes the option and the row together.
     */
    private fun headerOptionRegion(
        k: Int,
        blocks: List<DiffBlock>,
        side: EditRegions.Side,
        own: Block,
        removed: Boolean,
    ): EditRegion? {
        val table = own.editGroups.lastOrNull { it.kind == BlockGroupKind.TABLE } ?: return null
        if (!firstTableRow(own, table, blocks, side)) return null
        val header = own.key.startsWith("th:") ||
            blocks.any { it.status == DiffStatus.ADDED && it.right?.key?.startsWith("th:") == true }
        if (!header) return null
        val range = LineRange(table.startLine, own.source.endLine)
        return if (removed) EditRegion(range, range) else EditRegion(range, range)
    }

    private fun firstTableRow(own: Block, table: BlockGroup, blocks: List<DiffBlock>, side: EditRegions.Side): Boolean {
        val rows = blocks.indices.filter { i ->
            val block = side.block(i) ?: return@filter false
            block.kind == BlockKind.TABLE_ROW && block.editGroups.contains(table)
        }
        return rows.minOrNull()?.let { side.block(it) === own } == true
    }

    /** A block title exchanges only its line; on the other side it goes where the block it titles starts. */
    private fun titleRegion(
        k: Int,
        blocks: List<DiffBlock>,
        side: EditRegions.Side,
        other: EditRegions.Side,
        title: Block,
    ): EditRegion {
        val own = LineRange(title.source.startLine, title.source.endLine)
        val next = other.next(k)
        val anchor = if (next != null) {
            val block = other.block(next)!!
            val group = block.editGroups.firstOrNull { it.kind in STRUCTURE && it.startLine < block.source.startLine }
            group?.startLine ?: editStart(block, next, blocks, other)
        } else {
            other.lineCount
        }
        val gap = LineRange(anchor, anchor)
        return EditRegion(if (title === blocks[k].left) own else gap, if (title === blocks[k].right) own else gap)
    }

    /**
     * A code line added or removed inside a delimited block goes just before the line that follows it, or before
     * the closing delimiter at the end; both delimiters stay untouched.
     */
    private fun codeLineRegion(
        k: Int,
        blocks: List<DiffBlock>,
        side: EditRegions.Side,
        other: EditRegions.Side,
        code: BlockGroup?,
        removed: Boolean,
    ): EditRegion {
        val own = span(k, k, side, side.block(k)!!, inside = true, blocks = blocks)
        val bounds = code?.let(::interior)
        val ownRange = clamp(own, bounds)
        val gap = clamp(span(k, k, other, side.block(k)!!, inside = true, blocks = blocks), bounds)
        return if (removed) EditRegion(ownRange, gap) else EditRegion(gap, ownRange)
    }

    private data class GroupSelection(val group: BlockGroup, val first: Int, val last: Int)

    /** The outermost group the block belongs to whose every block on this side was added or removed. */
    private fun wholeGroup(k: Int, blocks: List<DiffBlock>, side: EditRegions.Side): GroupSelection? {
        val block = blocks[k]
        val groups = side.block(k)?.editGroups.orEmpty()
            .sortedWith(compareBy<BlockGroup> { it.startLine }.thenByDescending { it.endLine })
        for (group in groups) {
            val members = blocks.indices.filter { i -> side.block(i)?.editGroups?.contains(group) == true }
            val first = members.minOrNull() ?: continue
            val last = members.maxOrNull() ?: continue
            if (k !in first..last) continue
            val whole = (first..last).all { i ->
                blocks[i].status == block.status && side.block(i)?.editGroups?.contains(group) == true
            }
            if (whole) return GroupSelection(group, first, last)
        }
        return null
    }

    private fun groupRegion(
        k: Int,
        blocks: List<DiffBlock>,
        side: EditRegions.Side,
        other: EditRegions.Side,
        selection: GroupSelection,
        removed: Boolean,
    ): EditRegion {
        val own = side.block(k)!!
        val ownBounds = enclosingBounds(own, selected = selection.group)
        val changed = clamp(span(selection.first, selection.last, side, own, inside = false, blocks = blocks), ownBounds)
        val otherBounds = otherSideBounds(k, blocks, other, own, selected = selection.group)
        val gap = clamp(span(selection.first, selection.last, other, own, inside = false, blocks = blocks), otherBounds)
        return if (removed) EditRegion(changed, gap) else EditRegion(gap, changed)
    }

    /**
     * Like [EditRegions.Side.span], but the region stops before the next block's own group prefix and delimiters.
     * A removed list item must not take the following table's `[cols]` line and `|===` with it, and an added
     * paragraph must not take the opening delimiter of the container that follows. Consecutive changed blocks on
     * one side meet exactly at a block's start, so applying all their regions in one pass does not overlap.
     */
    private fun span(
        first: Int,
        last: Int,
        side: EditRegions.Side,
        current: Block,
        inside: Boolean,
        blocks: List<DiffBlock>,
    ): LineRange {
        val end = side.next(last)?.let { i -> blockStart(side.block(i)!!, current) } ?: side.lineCount
        val previous = side.previous(first)
        val start = if (previous != null) {
            val block = side.block(previous)!!
            val continuesRun = side.block(first) != null && blocks[previous].status == blocks[first].status
            when {
                continuesRun -> regionEnd(previous, side, block, blocks)
                inside && block.kind in CODE_KINDS -> block.source.endLine
                else -> side.extendedEnd(previous)
            }
        } else {
            0
        }
        return LineRange(minOf(start, end), end)
    }

    /** Where the region of the block at diff index [i] ends, before the next block's own group prefix. */
    private fun regionEnd(i: Int, side: EditRegions.Side, block: Block, blocks: List<DiffBlock>): Int =
        side.next(i)?.let { next -> blockStart(side.block(next)!!, block) } ?: side.lineCount

    /** The first line that belongs to [next] and not to [current]'s own groups. */
    private fun blockStart(next: Block, current: Block): Int {
        var start = next.source.startLine
        next.prefixLines.minOrNull()?.let { start = minOf(start, it) }
        for (group in next.editGroups) {
            if (group.kind !in STRUCTURE) continue
            if (sharesGroup(current, next, group)) continue
            start = minOf(start, group.startLine)
        }
        return start
    }

    /** True when [group], on the other side, is the counterpart of one of [current]'s own groups. */
    private fun sharesGroup(current: Block, next: Block, group: BlockGroup): Boolean = when (group.kind) {
        BlockGroupKind.CONTAINER -> current.context.containers.size >= next.context.containers.size
        BlockGroupKind.CODE -> current.editGroups.any { it.kind == BlockGroupKind.CODE }
        BlockGroupKind.TABLE -> current.editGroups.any { it.kind == BlockGroupKind.TABLE }
        BlockGroupKind.LIST_ITEM, BlockGroupKind.HEADER -> true
    }

    /** A modified block's region starts at its metadata lines, unless another changed block owns one of them. */
    private fun editStart(
        block: Block,
        k: Int,
        blocks: List<DiffBlock>,
        side: EditRegions.Side,
        otherBlock: Block? = null,
        otherSide: EditRegions.Side? = null,
    ): Int {
        val changed = changedLines(k, blocks, side)
        var start = block.prefixLines.filter { it !in changed }.minOrNull() ?: block.source.startLine
        if (block.kind == BlockKind.TABLE_ROW && block.key.startsWith("th:")) {
            val table = block.editGroups.lastOrNull { it.kind == BlockGroupKind.TABLE }
            if (table != null) start = minOf(start, table.startLine)
        }
        // A container's own style line (`[WARNING]`, a title) belongs to the edit of its first inner block when
        // that line is what changed, for example a changed admonition type.
        if (otherBlock != null && otherSide != null) {
            val otherGroups = otherBlock.editGroups
            for ((index, group) in block.editGroups.withIndex()) {
                if (group.kind != BlockGroupKind.CONTAINER || group.prefixLines.isEmpty()) continue
                if (!firstMember(k, group, blocks, side)) continue
                val other = otherGroups.getOrNull(index) ?: continue
                if (other.kind != group.kind) continue
                if (group.prefixLines.map(side::line) != other.prefixLines.map(otherSide::line)) {
                    start = minOf(start, group.startLine)
                }
            }
        }
        return start
    }

    private fun firstMember(k: Int, group: BlockGroup, blocks: List<DiffBlock>, side: EditRegions.Side): Boolean =
        (k - 1 downTo 0).none { i -> side.block(i)?.editGroups?.contains(group) == true }

    private fun changedLines(k: Int, blocks: List<DiffBlock>, side: EditRegions.Side): Set<Int> {
        val lines = mutableSetOf<Int>()
        for (i in blocks.indices) {
            if (i == k || blocks[i].status == DiffStatus.EQUAL) continue
            val block = side.block(i) ?: continue
            for (line in block.source.startLine until block.source.endLine) lines += line
        }
        return lines
    }

    /** The innermost existing container, code or table group around [block], excluding [selected]. */
    private fun enclosingBounds(block: Block, selected: BlockGroup?): LineRange? =
        block.editGroups.lastOrNull { it.kind in STRUCTURE && it != selected }?.let(::interior)

    /**
     * The interior of an existing group around the gap on the other side. The nearest block that stays on that
     * side tells which of the changed block's groups also exists there: a table row, the container around a first
     * or last inner block, and so on. Without one, the gap may use the lines up to the neighbor.
     */
    private fun otherSideBounds(
        k: Int,
        blocks: List<DiffBlock>,
        other: EditRegions.Side,
        changed: Block,
        selected: BlockGroup?,
    ): LineRange? {
        for (i in listOfNotNull(other.next(k), other.previous(k))) {
            val neighbor = other.block(i) ?: continue
            val group = matchingGroup(changed, neighbor, selected) ?: continue
            return interior(group)
        }
        return null
    }

    private fun matchingGroup(changed: Block, neighbor: Block, selected: BlockGroup?): BlockGroup? {
        val candidates = changed.editGroups.filter { it.kind in STRUCTURE && it != selected }
        val table = candidates.lastOrNull { it.kind == BlockGroupKind.TABLE }
        if (table != null) neighbor.editGroups.lastOrNull { it.kind == BlockGroupKind.TABLE }?.let { return it }
        if (changed.context.containers == neighbor.context.containers) {
            val container = candidates.lastOrNull { it.kind == BlockGroupKind.CONTAINER }
            if (container != null) {
                neighbor.editGroups.lastOrNull { it.kind == BlockGroupKind.CONTAINER }?.let { return it }
            }
        }
        val code = candidates.lastOrNull { it.kind == BlockGroupKind.CODE }
        if (code != null && neighbor.kind in CODE_KINDS) {
            neighbor.editGroups.lastOrNull { it.kind == BlockGroupKind.CODE }?.let { return it }
        }
        return null
    }

    private fun interior(group: BlockGroup): LineRange {
        val start = (group.openLine + 1).coerceAtLeast(group.startLine)
        val end = group.closeLine ?: group.endLine
        return LineRange(start, end.coerceAtLeast(start))
    }

    private fun clamp(range: LineRange, bounds: LineRange?): LineRange {
        if (bounds == null) return range
        val start = range.start.coerceIn(bounds.start, bounds.end)
        val end = range.end.coerceIn(start, bounds.end)
        return LineRange(start, end)
    }

    private val STRUCTURE = setOf(BlockGroupKind.CONTAINER, BlockGroupKind.CODE, BlockGroupKind.TABLE)
    private val CODE_KINDS = setOf(BlockKind.CODE_LINE, BlockKind.CODE_FENCE)
}
