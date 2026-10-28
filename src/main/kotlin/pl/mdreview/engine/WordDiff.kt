package pl.mdreview.engine

import com.github.difflib.DiffUtils

/** Word-level diff of two strings, used inside modified blocks. */
object WordDiff {

    private val token = Regex("""[\p{L}\p{N}_]+|\s+|.""")

    fun tokenize(text: String): List<String> = token.findAll(text).map { it.value }.toList()

    /** [tokenize], except that each of [atoms] (sorted ranges of [text], such as inline formulas) is one token. */
    fun tokenize(text: String, atoms: List<IntRange>): List<String> {
        if (atoms.isEmpty()) return tokenize(text)
        val tokens = mutableListOf<String>()
        var pos = 0
        for (atom in atoms) {
            if (atom.first < pos || atom.last >= text.length) continue
            tokens += tokenize(text.substring(pos, atom.first))
            tokens += text.substring(atom.first, atom.last + 1)
            pos = atom.last + 1
        }
        tokens += tokenize(text.substring(pos))
        return tokens
    }

    fun diff(left: String, right: String, leftAtoms: List<IntRange> = emptyList(), rightAtoms: List<IntRange> = emptyList()): List<InlineSegment> {
        val a = tokenize(left, leftAtoms)
        val b = tokenize(right, rightAtoms)
        val segments = mutableListOf<InlineSegment>()
        var i = 0
        var j = 0
        for (delta in DiffUtils.diff(a, b).deltas) {
            val sourcePos = delta.source.position
            val targetPos = delta.target.position
            if (sourcePos > i) add(segments, a.subList(i, sourcePos), DiffStatus.EQUAL)
            add(segments, delta.source.lines, DiffStatus.REMOVED)
            add(segments, delta.target.lines, DiffStatus.ADDED)
            i = sourcePos + delta.source.size()
            j = targetPos + delta.target.size()
        }
        if (i < a.size) add(segments, a.subList(i, a.size), DiffStatus.EQUAL)
        check(j <= b.size)
        return absorbLoneSpaces(segments)
    }

    /** Dice coefficient over case-insensitive word tokens; 1.0 for two texts without words. */
    fun similarity(left: String, right: String): Double {
        val a = words(left)
        val b = words(right)
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return dice(a, b)
    }

    private fun dice(a: List<String>, b: List<String>): Double {
        val counts = a.groupingBy { it }.eachCount().toMutableMap()
        var common = 0
        for (w in b) {
            val c = counts[w] ?: 0
            if (c > 0) {
                common++
                counts[w] = c - 1
            }
        }
        return 2.0 * common / (a.size + b.size)
    }

    /**
     * [similarity] over content words only, so two short texts are not paired just because they share
     * "the", "of" or "a". Falls back to all words when either text has no content words.
     */
    fun pairingSimilarity(left: String, right: String): Double {
        val a = words(left).filterNot { it in COMMON_WORDS }
        val b = words(right).filterNot { it in COMMON_WORDS }
        if (a.isEmpty() || b.isEmpty()) return similarity(left, right)
        return dice(a, b)
    }

    private fun words(text: String): List<String> =
        tokenize(text).filter { it.first().isLetterOrDigit() || it.first() == '_' }.map { it.lowercase() }

    private fun add(out: MutableList<InlineSegment>, tokens: List<String>, status: DiffStatus) {
        if (tokens.isEmpty()) return
        val text = tokens.joinToString("")
        val last = out.lastOrNull()
        if (last != null && last.status == status) {
            out[out.size - 1] = InlineSegment(last.text + text, status)
        } else {
            out += InlineSegment(text, status)
        }
    }

    /**
     * Turns "removed A, equal ' ', removed B" into one removed run, so a rewritten phrase reads as
     * one change instead of a word salad.
     */
    private fun absorbLoneSpaces(segments: List<InlineSegment>): List<InlineSegment> {
        if (segments.size < 3) return segments
        val out = mutableListOf<InlineSegment>()
        var k = 0
        while (k < segments.size) {
            val s = segments[k]
            val prevChanged = out.lastOrNull()?.status?.let { it != DiffStatus.EQUAL } ?: false
            val next = segments.getOrNull(k + 1)
            if (s.status == DiffStatus.EQUAL && s.text.isBlank() && prevChanged && next != null &&
                next.status != DiffStatus.EQUAL && changesBothSides(out, segments, k)
            ) {
                // Move the space into both sides of the change: drop it from "equal", append to the
                // removed and added text around it.
                val removed = mutableListOf<String>()
                val added = mutableListOf<String>()
                while (out.isNotEmpty() && out.last().status != DiffStatus.EQUAL) {
                    val p = out.removeAt(out.size - 1)
                    if (p.status == DiffStatus.REMOVED) removed.add(0, p.text) else added.add(0, p.text)
                }
                removed += s.text
                added += s.text
                var m = k + 1
                while (m < segments.size && segments[m].status != DiffStatus.EQUAL) {
                    if (segments[m].status == DiffStatus.REMOVED) removed += segments[m].text else added += segments[m].text
                    m++
                }
                out += InlineSegment(removed.joinToString(""), DiffStatus.REMOVED)
                out += InlineSegment(added.joinToString(""), DiffStatus.ADDED)
                k = m
                continue
            }
            out += s
            k++
        }
        return out.filter { it.text.isNotEmpty() }
    }

    /** True when the changes on both sides of segment [k] together remove and add real words. */
    private fun changesBothSides(before: List<InlineSegment>, segments: List<InlineSegment>, k: Int): Boolean {
        val around = before.takeLastWhile { it.status != DiffStatus.EQUAL } +
            segments.drop(k + 1).takeWhile { it.status != DiffStatus.EQUAL }
        return around.any { it.status == DiffStatus.REMOVED && it.text.isNotBlank() } &&
            around.any { it.status == DiffStatus.ADDED && it.text.isNotBlank() }
    }

    /** English function words that carry no meaning on their own (NFR-10: documents are mostly English). */
    private val COMMON_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by", "for", "with", "from", "as",
        "is", "are", "was", "were", "be", "been", "it", "its", "this", "that", "these", "those", "into", "than", "then",
    )
}
