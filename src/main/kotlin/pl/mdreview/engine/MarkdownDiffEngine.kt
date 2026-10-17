package pl.mdreview.engine

import com.github.difflib.DiffUtils

/**
 * Compares two Markdown documents block by block.
 *
 * 1. Both sides are flattened into blocks ([BlockExtractor]).
 * 2. A Myers/LCS diff on block keys finds the unchanged blocks.
 * 3. Inside each changed window, removed and added blocks of the same kind are paired in order
 *    when their word similarity reaches [DiffOptions.pairingThreshold]; pairs become MODIFIED
 *    blocks with a word diff, the rest stay REMOVED or ADDED.
 */
class MarkdownDiffEngine(private val options: DiffOptions = DiffOptions()) {

    fun diff(left: String, right: String): MarkdownDiff =
        diff(BlockExtractor.extract(left), BlockExtractor.extract(right))

    fun diff(left: List<Block>, right: List<Block>): MarkdownDiff {
        val leftKeys = left.map(::compareKey)
        val rightKeys = right.map(::compareKey)
        val result = mutableListOf<DiffBlock>()
        var i = 0
        var j = 0
        for (delta in DiffUtils.diff(leftKeys, rightKeys).deltas) {
            val ls = delta.source.position
            val rs = delta.target.position
            while (i < ls && j < rs) result += DiffBlock(DiffStatus.EQUAL, left[i++], right[j++])
            pairWindow(
                left.subList(ls, ls + delta.source.size()),
                right.subList(rs, rs + delta.target.size()),
                result,
            )
            i = ls + delta.source.size()
            j = rs + delta.target.size()
        }
        while (i < left.size && j < right.size) result += DiffBlock(DiffStatus.EQUAL, left[i++], right[j++])
        return MarkdownDiff(result)
    }

    private fun compareKey(block: Block): String =
        if (options.ignoreWhitespace) block.key.filterNot { it.isWhitespace() } else block.key

    private fun pairWindow(removed: List<Block>, added: List<Block>, out: MutableList<DiffBlock>) {
        val pairs = findPairs(removed, added)
        var r = 0
        var a = 0
        for ((pr, pa) in pairs) {
            emitGap(removed.subList(r, pr), added.subList(a, pa), out)
            r = pr
            a = pa
            val l = removed[r++]
            val rt = added[a++]
            out += DiffBlock(DiffStatus.MODIFIED, l, rt, WordDiff.diff(l.plainText, rt.plainText))
        }
        emitGap(removed.subList(r, removed.size), added.subList(a, added.size), out)
    }

    /**
     * Unpaired blocks between two pairs: removed first, then added, except that blocks continuing
     * the previous block (more lines of the same code block, items of the same list, rows of the same
     * table) come first, so a line added at the end of a code block stays with that block.
     */
    private fun emitGap(removed: List<Block>, added: List<Block>, out: MutableList<DiffBlock>) {
        // One block replaced by one block of the same kind: most likely a rewrite of it.
        if (removed.size == 1 && added.size == 1 &&
            pairScore(removed[0], added[0], lone = true, bothLone = true) > 0
        ) {
            out += DiffBlock(DiffStatus.MODIFIED, removed[0], added[0], WordDiff.diff(removed[0].plainText, added[0].plainText))
            return
        }
        val previous = out.lastOrNull()?.let { it.right ?: it.left }
        fun continues(b: Block) = previous != null && b.kind == previous.kind && b.kind in CONTINUING_KINDS
        val removedLead = removed.takeWhile(::continues)
        val addedLead = added.takeWhile(::continues)
        removedLead.forEach { out += DiffBlock(DiffStatus.REMOVED, it, null) }
        addedLead.forEach { out += DiffBlock(DiffStatus.ADDED, null, it) }
        removed.drop(removedLead.size).forEach { out += DiffBlock(DiffStatus.REMOVED, it, null) }
        added.drop(addedLead.size).forEach { out += DiffBlock(DiffStatus.ADDED, null, it) }
    }

    /**
     * Order-preserving pairing: the best-scoring set of (removed, added) index pairs where both
     * indices increase, each pair has the same kind and context and reaches the threshold.
     * Dynamic programming over the window, which is small in practice.
     */
    private fun findPairs(removed: List<Block>, added: List<Block>): List<Pair<Int, Int>> {
        if (removed.isEmpty() || added.isEmpty()) return emptyList()
        if (removed.size * added.size > MAX_PAIRING_CELLS) return greedyPairs(removed, added)
        val n = removed.size
        val m = added.size
        // Headings of different levels count as different kinds here.
        fun kindOf(b: Block) = if (b.kind == BlockKind.HEADING) "h${b.level}" else b.kind.name
        val removedKinds = removed.groupingBy(::kindOf).eachCount()
        val addedKinds = added.groupingBy(::kindOf).eachCount()
        val score = Array(n) { r ->
            DoubleArray(m) { a ->
                val lone = removedKinds[kindOf(removed[r])] == 1 || addedKinds[kindOf(added[a])] == 1
                val bothLone = removedKinds[kindOf(removed[r])] == 1 && addedKinds[kindOf(added[a])] == 1
                pairScore(removed[r], added[a], lone, bothLone)
            }
        }
        val best = Array(n + 1) { DoubleArray(m + 1) }
        for (r in n - 1 downTo 0) {
            for (a in m - 1 downTo 0) {
                val take = if (score[r][a] > 0) score[r][a] + best[r + 1][a + 1] else 0.0
                best[r][a] = maxOf(take, best[r + 1][a], best[r][a + 1])
            }
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var r = 0
        var a = 0
        while (r < n && a < m) {
            val s = score[r][a]
            when {
                s > 0 && best[r][a] == s + best[r + 1][a + 1] -> { pairs += r to a; r++; a++ }
                best[r][a] == best[r + 1][a] -> r++
                else -> a++
            }
        }
        return pairs
    }

    private fun greedyPairs(removed: List<Block>, added: List<Block>): List<Pair<Int, Int>> {
        val pairs = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (r in removed.indices) {
            for (a in start until added.size) {
                if (pairScore(removed[r], added[a], lone = false, bothLone = false) > 0) {
                    pairs += r to a
                    start = a + 1
                    break
                }
            }
        }
        return pairs
    }

    /**
     * How well [l] and [r] pair as one modified block; 0 means they do not pair.
     *
     * [lone] is true when one of them is the only block of its kind on its side of the window: a
     * single rewritten paragraph is likely the same paragraph, so a lower similarity is enough.
     * [bothLone] means each is the only one of its kind on its side.
     */
    private fun pairScore(l: Block, r: Block, lone: Boolean, bothLone: Boolean): Double {
        if (!compatible(l, r)) return 0.0
        val s = WordDiff.similarity(l.plainText, r.plainText)
        when (l.kind) {
            // A fence only carries the language; a changed fence is always the same code block.
            BlockKind.CODE_FENCE -> return MIN_SCORE
            BlockKind.TABLE_ROW -> {
                // Rows are identified by their first cell (usually the row's name); header rows by position.
                val header = l.key.startsWith("th:")
                return when {
                    header -> s + 1.0
                    firstCell(l) == firstCell(r) -> s + 1.0
                    else -> 0.0
                }
            }
            // A renamed heading at the same level, with no other heading around, is the same heading.
            BlockKind.HEADING -> if (bothLone && l.level == r.level) return maxOf(s, MIN_SCORE)
            else -> Unit
        }
        val threshold = if (lone) options.pairingThreshold / 2 else options.pairingThreshold
        return if (s >= threshold && s > 0) s else 0.0
    }

    private fun firstCell(block: Block): String =
        block.rawText.trim().removePrefix("|").substringBefore('|').trim()

    private fun compatible(l: Block, r: Block): Boolean {
        if (l.kind != r.kind) return false
        if (l.context.quoteDepth != r.context.quoteDepth) return false
        // A header row only pairs with a header row. Moving a list item to another nesting level or
        // list type still counts as a modification.
        if (l.kind == BlockKind.TABLE_ROW) return l.key.take(3) == r.key.take(3)
        return true
    }

    companion object {
        private const val MAX_PAIRING_CELLS = 250_000
        private const val MIN_SCORE = 0.01
        private val CONTINUING_KINDS = setOf(BlockKind.CODE_LINE, BlockKind.FRONT_MATTER, BlockKind.LIST_ITEM, BlockKind.TABLE_ROW)
    }
}
