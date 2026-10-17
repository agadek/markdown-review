package pl.mdreview.api

/**
 * Contract between the core (diff engine + HTML generator) and the IDE layer (`pl.mdreview.ide`).
 *
 * This package must stay free of IntelliJ Platform dependencies so the core can implement and test it
 * with plain unit tests. The IDE layer only talks to [MarkdownDiffRenderer]; it never touches the
 * commonmark AST or the engine's own model.
 *
 * The implementation is discovered with [java.util.ServiceLoader]: the core registers its class in
 * `src/main/resources/META-INF/services/pl.mdreview.api.MarkdownDiffRenderer`. Until it does, the IDE
 * layer falls back to a plain-text renderer, so the plugin stays runnable. See `docs/ENGINE_CONTRACT.md`.
 */
interface MarkdownDiffRenderer {
    /**
     * Compares two Markdown sources and renders the result.
     *
     * Called off the EDT, inside a read action; must be pure (no IntelliJ APIs, no I/O, no network)
     * and thread-safe, since several diff windows may render at once. Long computations should be
     * fine to abandon: the IDE layer discards results that arrive after the input changed.
     */
    fun render(request: RenderRequest): RenderedDiff
}

enum class ViewMode {
    /** Two columns, one grid row per block, rows aligned (FR-1, FR-2, FR-3). */
    SIDE_BY_SIDE,

    /** One column with `<del>`/`<ins>`, removed blocks struck through (FR-4). */
    MERGED,
}

data class DiffOptions(
    /** Ignore whitespace and source-only reflow, e.g. re-wrapped paragraphs (FR-7). */
    val ignoreWhitespace: Boolean = false,
    /** Collapse runs of more than 5 unchanged blocks into an expandable bar. */
    val collapseUnchanged: Boolean = true,
    /** Keep `http(s)` image sources in raw HTML instead of blanking them (NFR-8); the page's CSP must allow them too. */
    val loadRemoteImages: Boolean = false,
    /**
     * Keep each ` ```mermaid ` fence as one block, emitted as an inert `.mdr-mermaid` element that the IDE
     * layer's page script draws; off, the fence is diffed line by line like any other code block.
     */
    val renderMermaid: Boolean = true,
    /**
     * Minimum word similarity (0..1) for a removed and an added block to be shown as one modified block.
     * Lower pairs more rewritten blocks; higher shows more of them as removed plus added.
     */
    val pairingThreshold: Double = DEFAULT_PAIRING_THRESHOLD,
) {
    companion object {
        /** The one place the default pairing threshold is defined; the engine and the settings use it. */
        const val DEFAULT_PAIRING_THRESHOLD = 0.5
        const val MIN_PAIRING_THRESHOLD = 0.05
        const val MAX_PAIRING_THRESHOLD = 0.95
    }
}

/**
 * @property left Markdown of the left (old) side, `null` when that file does not exist (new file).
 *   Line separators are already normalized to `\n`.
 * @property right Markdown of the right (new) side, `null` when that file does not exist (deleted file).
 * @property leftBaseUri URI of the directory holding the left file, ending with `/` (for example
 *   `file:///home/me/project/docs/`). Relative image paths on that side resolve against it; `null`
 *   leaves them as written.
 * @property rightBaseUri the same for the right side.
 */
data class RenderRequest(
    val left: String?,
    val right: String?,
    val mode: ViewMode,
    val options: DiffOptions = DiffOptions(),
    val leftBaseUri: String? = null,
    val rightBaseUri: String? = null,
)

/**
 * Size above which a side is not rendered (NFR-4): the viewer shows a notice with a way to the text
 * viewer instead of a panel that freezes on a huge document.
 */
data class SizeLimit(val maxLines: Int = DEFAULT_MAX_LINES, val maxBytes: Long = DEFAULT_MAX_BYTES) {

    /** True when [text] has more than [maxLines] lines or more than [maxBytes] bytes in UTF-8. */
    fun exceeds(text: CharSequence?): Boolean {
        if (text == null) return false
        // Every char takes at least one byte, and at most three (a surrogate pair takes four for two chars).
        if (text.length > maxBytes) return true
        var lines = 1
        var bytes = 0L
        for (c in text) {
            if (c == '\n' && ++lines > maxLines) return true
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 || c.isSurrogate() -> 2
                else -> 3
            }
        }
        return bytes > maxBytes
    }

    companion object {
        const val DEFAULT_MAX_LINES = 20_000
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
        val DEFAULT = SizeLimit()
    }
}

enum class BlockStatus { EQUAL, MODIFIED, REMOVED, ADDED }

/** Zero-based, end-exclusive line range in the source text of one side. */
data class LineRange(val start: Int, val end: Int)

/**
 * One aligned unit of the diff: a row in the side-by-side grid, a block in the merged view.
 *
 * @property idx position in [RenderedDiff.blocks]; equals the `data-idx` attribute in the HTML.
 * @property left source lines on the left side, `null` for [BlockStatus.ADDED].
 * @property right source lines on the right side, `null` for [BlockStatus.REMOVED].
 * @property edit the lines that per-block revert and apply exchange (FR-12), `null` for [BlockStatus.EQUAL].
 */
data class BlockInfo(
    val idx: Int,
    val status: BlockStatus,
    val left: LineRange?,
    val right: LineRange?,
    val edit: EditRegion? = null,
)

/**
 * Line ranges, one per side, that make a block's change undone when one replaces the other: replacing [right]
 * with the lines of [left] reverts the change on the right side, replacing [left] with [right] applies it to the
 * left side.
 *
 * For a [BlockStatus.MODIFIED] block these are its own lines. For an added or removed block, the side holding it
 * covers the block with the blank lines around it, and the other side covers the gap where it would go, which may
 * be empty (`start == end`): an insertion. A fenced code block, front matter or table added or removed as a whole
 * is one region for all its blocks, including the closing fence or delimiter row, so any of its blocks reverts all
 * of it. Ranges are zero-based and end-exclusive over the lines of the side's text split at `\n`.
 */
data class EditRegion(val left: LineRange, val right: LineRange)

/** Replace [start, end) of a text with [replacement]. */
data class TextEdit(val start: Int, val end: Int, val replacement: String) {
    companion object {
        /**
         * The edit that replaces the lines [targetLines] of [target] with the lines [sourceLines] of [source].
         * Both texts use `\n` only; lines are the text split at `\n`, so a trailing line break ends with an
         * empty last line, and an empty text has one empty line.
         */
        fun replaceLines(target: CharSequence, targetLines: LineRange, source: CharSequence, sourceLines: LineRange): TextEdit {
            val starts = lineStarts(target)
            val n = starts.size
            fun lineEnd(line: Int) = if (line + 1 < n) starts[line + 1] - 1 else target.length
            val sourceStarts = lineStarts(source)
            val lines = (sourceLines.start until sourceLines.end).map { line ->
                val end = if (line + 1 < sourceStarts.size) sourceStarts[line + 1] - 1 else source.length
                source.subSequence(sourceStarts[line], end)
            }
            val text = lines.joinToString("\n")
            val a = targetLines.start
            val b = targetLines.end
            require(a in 0..b && b <= n) { "Line range $targetLines outside a text of $n lines" }
            return when {
                a < b && lines.isNotEmpty() -> TextEdit(starts[a], lineEnd(b - 1), text)
                a < b && b < n -> TextEdit(starts[a], starts[b], "")
                a < b && a > 0 -> TextEdit(lineEnd(a - 1), lineEnd(b - 1), "")
                a < b -> TextEdit(0, target.length, "")
                lines.isEmpty() -> TextEdit(0, 0, "")
                target.isEmpty() -> TextEdit(0, 0, text)
                a < n -> TextEdit(starts[a], starts[a], text + "\n")
                else -> TextEdit(target.length, target.length, "\n" + text)
            }
        }

        private fun lineStarts(text: CharSequence): IntArray {
            val starts = ArrayList<Int>()
            starts += 0
            for (i in text.indices) if (text[i] == '\n') starts += i + 1
            return starts.toIntArray()
        }
    }

    fun applyTo(text: String): String = text.substring(0, start) + replacement + text.substring(end)
}

/**
 * @property bodyHtml HTML fragment placed inside `<body>`. Must not contain scripts or event handler
 *   attributes: the page runs under a CSP that allows only the IDE layer's own script.
 *   DOM contract (the IDE layer's navigation, scrolling and click handling rely on it):
 *   - every block element (grid row / merged block) carries `data-idx="<BlockInfo.idx>"` and the class
 *     `blk-equal`, `blk-modified`, `blk-removed` or `blk-added`;
 *   - every element holding one side's content carries `data-side="left|right"` and
 *     `data-line="<zero-based start line>"` (placeholders carry `data-side` only);
 *   - collapsed unchanged runs use `<details>`/`<summary>` (no JavaScript needed);
 *   - links stay plain `<a href>`; the IDE layer intercepts clicks.
 * @property css stylesheet for [bodyHtml]. Colors and fonts come only from these CSS variables, which
 *   the IDE layer defines from the current editor color scheme (NFR-5):
 *   `--md-bg`, `--md-fg`, `--md-muted`, `--md-border`, `--md-link`, `--md-code-bg`,
 *   `--md-removed-bg`, `--md-added-bg`, `--md-modified-bg`, `--md-placeholder-bg`,
 *   `--md-removed-word-bg`, `--md-added-word-bg`, `--md-font`, `--md-font-size`, `--md-mono-font`.
 *   Use `prefers-color-scheme` nowhere; the IDE decides light/dark through the variables.
 * @property blocks every block in display order; the IDE derives change navigation (FR-6) from the
 *   non-[BlockStatus.EQUAL] entries and source navigation (FR-14) from the line ranges.
 */
data class RenderedDiff(
    val bodyHtml: String,
    val css: String,
    val blocks: List<BlockInfo>,
) {
    val changes: List<BlockInfo> get() = blocks.filter { it.status != BlockStatus.EQUAL }
    val identical: Boolean get() = blocks.all { it.status == BlockStatus.EQUAL }
}
