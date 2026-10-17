package pl.mdreview.ide

import pl.mdreview.api.BlockInfo
import pl.mdreview.api.BlockStatus
import pl.mdreview.api.LineRange
import pl.mdreview.api.MarkdownDiffRenderer
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.RenderedDiff
import pl.mdreview.api.ViewMode

/**
 * Stand-in used until the core registers its [MarkdownDiffRenderer]: splits both sides into
 * blank-line separated chunks, matches them with LCS and shows the raw source. No Markdown rendering,
 * no word diff; it exists only so the IDE layer can be run and checked end to end.
 */
class FallbackRenderer : MarkdownDiffRenderer {

    private data class Chunk(val text: String, val range: LineRange)

    override fun render(request: RenderRequest): RenderedDiff {
        val left = chunks(request.left.orEmpty(), request.options.ignoreWhitespace)
        val right = chunks(request.right.orEmpty(), request.options.ignoreWhitespace)
        val blocks = align(left, right)
        val body = StringBuilder()
        if (request.mode == ViewMode.SIDE_BY_SIDE) body.append("<div class=\"grid\">")
        for ((i, b) in blocks.withIndex()) {
            val (status, l, r) = b
            val cls = "blk-" + status.name.lowercase()
            if (request.mode == ViewMode.SIDE_BY_SIDE) {
                body.append("<div class=\"row $cls\" data-idx=\"$i\">")
                body.append(cell("left", l))
                body.append(cell("right", r))
                body.append("</div>")
            } else {
                val shown = r ?: l!!
                val side = if (r != null) "right" else "left"
                val text = if (status == BlockStatus.REMOVED) "<del>${esc(shown.text)}</del>" else esc(shown.text)
                body.append("<div class=\"$cls\" data-idx=\"$i\" data-side=\"$side\" data-line=\"${shown.range.start}\"><pre>$text</pre></div>")
            }
        }
        if (request.mode == ViewMode.SIDE_BY_SIDE) body.append("</div>")
        return RenderedDiff(
            bodyHtml = body.toString(),
            css = CSS,
            blocks = blocks.mapIndexed { i, (status, l, r) -> BlockInfo(i, status, l?.range, r?.range) },
        )
    }

    private fun cell(side: String, chunk: Chunk?): String =
        if (chunk == null) "<div class=\"cell blk-placeholder\" data-side=\"$side\"></div>"
        else "<div class=\"cell\" data-side=\"$side\" data-line=\"${chunk.range.start}\"><pre>${esc(chunk.text)}</pre></div>"

    private fun chunks(text: String, ignoreWhitespace: Boolean): List<Chunk> {
        val lines = text.split('\n')
        val result = mutableListOf<Chunk>()
        var start = -1
        for (i in 0..lines.size) {
            val blank = i == lines.size || lines[i].isBlank()
            if (!blank && start < 0) start = i
            if (blank && start >= 0) {
                var chunk = lines.subList(start, i).joinToString("\n")
                if (ignoreWhitespace) chunk = chunk.trim().replace(Regex("\\s+"), " ")
                result += Chunk(chunk, LineRange(start, i))
                start = -1
            }
        }
        return result
    }

    private fun align(left: List<Chunk>, right: List<Chunk>): List<Triple<BlockStatus, Chunk?, Chunk?>> {
        val n = left.size
        val m = right.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (left[i].text == right[j].text) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = mutableListOf<Triple<BlockStatus, Chunk?, Chunk?>>()
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && left[i].text == right[j].text -> out += Triple(BlockStatus.EQUAL, left[i++], right[j++])
                i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1]) -> out += Triple(BlockStatus.REMOVED, left[i++], null)
                else -> out += Triple(BlockStatus.ADDED, null, right[j++])
            }
        }
        return out
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        val CSS = """
            body { background: var(--md-bg); color: var(--md-fg); font-family: var(--md-font); font-size: var(--md-font-size); margin: 0; }
            pre { font-family: var(--md-mono-font); white-space: pre-wrap; margin: 0; }
            .grid { display: grid; grid-template-columns: 1fr 1fr; }
            .row { display: contents; }
            .cell, [data-idx]:not(.row) { padding: 4px 8px; border-bottom: 1px solid var(--md-border); }
            .blk-removed > [data-side=left], div.blk-removed:not(.row) { background: var(--md-removed-bg); }
            .blk-added > [data-side=right], div.blk-added:not(.row) { background: var(--md-added-bg); }
            .blk-placeholder { background: var(--md-placeholder-bg); }
        """.trimIndent()
    }
}
