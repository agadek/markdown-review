package pl.mdreview.render

import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Node
import org.commonmark.renderer.html.HtmlRenderer
import pl.mdreview.api.ViewMode
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContext
import pl.mdreview.engine.BlockExtractor
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.DiffBlock
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.HtmlFragment
import pl.mdreview.engine.MarkdownDiff
import pl.mdreview.engine.MarkdownDiffEngine

data class RenderOptions(
    val mode: ViewMode = ViewMode.SIDE_BY_SIDE,
    /** Fold runs of more than [foldThreshold] unchanged blocks, keeping [foldContext] on each side. */
    val collapseUnchanged: Boolean = true,
    val foldThreshold: Int = 5,
    val foldContext: Int = 2,
    val leftTitle: String? = null,
    val rightTitle: String? = null,
    val allowRemoteImages: Boolean = false,
    /** Base URI of each side's file (`file:///dir/`); relative image paths resolve against it. */
    val leftBaseUri: String? = null,
    val rightBaseUri: String? = null,
    val labels: Labels = Labels(),
)

data class Labels(
    val noDifferences: String = "No differences",
    val unchangedBlocks: (Int) -> String = { n -> if (n == 1) "1 unchanged block" else "$n unchanged blocks" },
    val code: String = "code",
    val frontMatter: String = "front matter",
    val checked: String = "checked",
    val unchecked: String = "unchecked",
    val headingLevel: (Int, Int) -> String = { from, to -> "h$from \u2192 h$to" },
    val previousUrl: (String) -> String = { url -> "Previous URL: $url" },
    val newUrl: (String) -> String = { url -> "New URL: $url" },
    val showSourceChanges: String = "Show source changes",
    /** Tooltip of an HTML comment row, which is only shown when the comment changed. */
    val comment: String = "HTML comment",
    val commentChanged: String = "comment changed",
    /** Note for HTML whose markup changed but whose visible text did not. */
    val markupChanged: String = "HTML changed",
    /** Summary of a `<details>` element without a `<summary>`, as browsers show it. */
    val details: String = "Details",
    /** Label on the new place of a moved block; true when the old place is further down. */
    val movedFrom: (Boolean) -> String = { below -> if (below) "Moved here from below" else "Moved here from above" },
    /** Label on the old place of a moved block; true when the new place is further down. */
    val movedTo: (Boolean) -> String = { below -> if (below) "Moved down" else "Moved up" },
)

/**
 * Turns a [MarkdownDiff] into HTML that follows two contracts:
 * - `src/main/resources/pl/mdreview/css/CLASSES.md`: the `mdr-*` classes styled by `diff.css`;
 * - `docs/ENGINE_CONTRACT.md`: `data-idx`, `blk-<status>`, `data-side` and `data-line`, which the IDE
 *   layer uses for navigation and source links.
 *
 * Each [DiffBlock] is one block element. In the side-by-side view it is a `.mdr-row` with a left and a
 * right `.mdr-side`, so both sides stay aligned without script. `data-idx` is the block's index in
 * [MarkdownDiff.blocks].
 */
class DiffHtmlRenderer(private val options: RenderOptions = RenderOptions()) {

    /** The fragment for `<body>`: no scripts, no event handlers. */
    fun renderBody(diff: MarkdownDiff): String {
        val sbs = options.mode == ViewMode.SIDE_BY_SIDE
        val body = StringBuilder()
        body.append("<div class=\"mdr-root ").append(if (sbs) "mdr-sbs" else "mdr-merged").append("\">\n")
        if (sbs && (options.leftTitle != null || options.rightTitle != null)) {
            body.append("<div class=\"mdr-sbs-header\"><div>").append(escape(options.leftTitle.orEmpty()))
                .append("</div><div>").append(escape(options.rightTitle.orEmpty())).append("</div></div>\n")
        }
        if (!diff.hasChanges) {
            body.append("<div class=\"mdr-empty\">").append(escape(options.labels.noDifferences)).append("</div>\n")
        }
        renderRange(diff, 0, diff.blocks.size, body)
        body.append("</div>\n")
        return body.toString()
    }

    /**
     * Renders blocks [from] until [to]. A multi-block `<details>` element becomes a real collapsible element around
     * its rows, open when anything in it changed; its summary row is the [BlockKind.DETAILS] block.
     */
    private fun renderRange(diff: MarkdownDiff, from: Int, to: Int, out: StringBuilder) {
        val blocks = diff.blocks
        var spanStart = from
        var k = from
        while (k < to) {
            val opener = detailsOpener(blocks[k])
            if (opener == null) {
                k++
                continue
            }
            renderSpan(diff, spanStart, k, out)
            var end = k + 1
            while (end < to && detailsDepth(blocks[end]) > opener.context.details) end++
            val open = (k until end).any { blocks[it].isChange }
            out.append("<details class=\"mdr-details\"").append(if (open) " open" else "")
                .append("><summary class=\"mdr-details-summary\">")
            out.append(block(blocks[k], k).trimEnd())
            out.append("</summary>\n")
            renderRange(diff, k + 1, end, out)
            out.append("</details>\n")
            k = end
            spanStart = end
        }
        renderSpan(diff, spanStart, to, out)
    }

    private fun detailsOpener(block: DiffBlock): Block? =
        listOfNotNull(block.left, block.right).firstOrNull { it.kind == BlockKind.DETAILS }

    private fun detailsDepth(block: DiffBlock): Int = maxOf(block.left?.context?.details ?: 0, block.right?.context?.details ?: 0)

    /** Blocks [from] until [to], none of which opens a `<details>`, with long unchanged runs folded. */
    private fun renderSpan(diff: MarkdownDiff, from: Int, to: Int, out: StringBuilder) {
        val folds = if (diff.hasChanges) foldRanges(diff.blocks, from, to) else emptyMap()
        var k = from
        while (k < to) {
            val fold = folds[k]
            if (fold != null) {
                out.append("<details class=\"mdr-fold\"><summary>")
                    .append(escape(options.labels.unchangedBlocks(fold.last - fold.first + 1))).append("</summary>\n")
                for (f in fold) out.append(block(diff.blocks[f], f))
                out.append("</details>\n")
                k = fold.last + 1
                continue
            }
            out.append(block(diff.blocks[k], k))
            k++
        }
    }

    /** Stylesheet for [renderBody]: `diff.css` plus layout rules, colors from the `--md-*` variables. */
    fun css(): String = Styles.css()

    /** A standalone page (previews, tests): [renderBody] with [theme] colors and no script. */
    fun renderPage(diff: MarkdownDiff, theme: Theme = Theme.LIGHT): String {
        val img = if (options.allowRemoteImages) "data: file: https: http:" else "data: file:"
        return buildString {
            append("<!doctype html>\n<html class=\"").append(if (theme.dark) "mdr-theme-dark" else "mdr-theme-light")
            append("\"><head><meta charset=\"utf-8\">\n")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src ")
            append(img).append("; style-src 'unsafe-inline'\">\n")
            append("<style>\n").append(theme.cssVariables()).append("\n</style>\n")
            append("<style>\n").append(css()).append("</style></head>\n<body>\n")
            append(renderBody(diff))
            append("</body></html>\n")
        }
    }

    /**
     * Ranges of indices in [start] until [stop] to fold, keyed by their first index. Context blocks are kept
     * around every run except at the start and end of the document. A document without changes is shown whole.
     */
    private fun foldRanges(blocks: List<DiffBlock>, start: Int, stop: Int): Map<Int, IntRange> {
        if (!options.collapseUnchanged) return emptyMap()
        val result = mutableMapOf<Int, IntRange>()
        var k = start
        while (k < stop) {
            if (blocks[k].isChange) { k++; continue }
            var end = k
            while (end + 1 < stop && !blocks[end + 1].isChange) end++
            val atStart = k == 0
            val atEnd = end == blocks.size - 1
            val from = if (atStart) k else k + options.foldContext
            val to = if (atEnd) end else end - options.foldContext
            if (end - k + 1 > options.foldThreshold && to - from + 1 >= 2) {
                result[from] = from..to
            }
            k = end + 1
        }
        return result
    }

    /**
     * One row (side by side) or item (merged). [index] is null for the rows of a diagram's source diff,
     * which are not blocks of their own and so carry no `data-idx`.
     */
    private fun block(block: DiffBlock, index: Int?): String {
        val status = block.status.name.lowercase()
        val idx = if (index == null) "" else " data-idx=\"$index\" data-block-id=\"b$index\"" + moveAttributes(block)
        if (isChangedDiagram(block)) return changedDiagram(block, status, idx)
        val moved = if (index != null && (block.movedFrom != null || block.movedTo != null)) " mdr-moved" else ""
        val moveNote = if (moved.isEmpty()) "" else moveNote(block, index!!)
        val sb = StringBuilder()
        when (options.mode) {
            ViewMode.SIDE_BY_SIDE -> {
                sb.append("<div class=\"mdr-row blk-").append(status).append(moved).append("\"").append(idx).append(hidden(block)).append(">")
                val links = linkChanges(block)
                sb.append(side("left", block.left, block.status, leftMarks(block), null, links.left, moveNote))
                sb.append(side("right", block.right, block.status, rightMarks(block, withRemoved = false), changeNote(block), links.right, moveNote))
                sb.append("</div>\n")
            }
            ViewMode.MERGED -> {
                val (content, side, marks) = when (block.status) {
                    DiffStatus.REMOVED -> Triple(block.left!!, "left", InlineMarks.NONE)
                    DiffStatus.MODIFIED -> Triple(block.right!!, "right", rightMarks(block, withRemoved = true))
                    else -> Triple(block.right!!, "right", InlineMarks.NONE)
                }
                sb.append("<div class=\"mdr-item blk-").append(status).append(moved).append("\"").append(idx).append(hidden(block)).append(" data-side=\"").append(side)
                    .append("\" data-line=\"").append(content.source.startLine).append("\">")
                val note = if (block.status == DiffStatus.MODIFIED) changeNote(block) else null
                val links = if (side == "right") linkChanges(block).right else emptyMap()
                sb.append(quote(renderBlock(content, block.status, marks, side, note, links), content.context))
                sb.append(moveNote)
                sb.append("</div>\n")
            }
        }
        return sb.toString()
    }

    private fun moveAttributes(block: DiffBlock): String =
        (block.movedFrom?.let { " data-moved-from=\"$it\"" } ?: "") + (block.movedTo?.let { " data-moved-to=\"$it\"" } ?: "")

    /**
     * The label on either end of a moved block (FR-15), a link to the other end. The page script scrolls to the block
     * whose `data-idx` is the link's `data-goto`; without it the link does nothing.
     */
    private fun moveNote(block: DiffBlock, index: Int): String {
        val other = block.movedFrom ?: block.movedTo ?: return ""
        val label = if (block.movedFrom != null) options.labels.movedFrom(other > index) else options.labels.movedTo(other > index)
        return "<div class=\"mdr-moved-note\"><a class=\"mdr-moved-link\" href=\"#\" data-goto=\"$other\">" +
            escape(label) + "</a></div>"
    }

    private fun isChangedDiagram(block: DiffBlock): Boolean {
        val kind = block.left?.kind
        return block.status == DiffStatus.MODIFIED && kind in DRAWN_KINDS && block.right?.kind == kind
    }

    /**
     * A modified diagram or block formula: the old and the new drawing (side by side, or stacked in the merged
     * view), and a "Show source changes" toggle that swaps them for the line diff of the source. The toggle is a
     * `<details>`, so it works without script; `diff.css` hides the drawings while it is open. The classes are
     * `mdr-mermaid-*` for a diagram and `mdr-math-*` for a formula.
     */
    private fun changedDiagram(block: DiffBlock, status: String, idx: String): String {
        val left = block.left!!
        val right = block.right!!
        val prefix = if (left.kind == BlockKind.MATH) "mdr-math" else "mdr-mermaid"
        val lines = MarkdownDiffEngine().diff(left.lines, right.lines).blocks
        val source = "<details class=\"$prefix-source\"><summary>" + escape(options.labels.showSourceChanges) +
            "</summary><div class=\"$prefix-lines\">" + lines.joinToString("") { block(it, null) } + "</div></details>"
        return when (options.mode) {
            ViewMode.SIDE_BY_SIDE -> "<div class=\"mdr-row blk-$status $prefix-row\"$idx>" + source +
                side("left", left, block.status, InlineMarks.NONE, null, emptyMap()) +
                side("right", right, block.status, InlineMarks.NONE, null, emptyMap()) + "</div>\n"
            ViewMode.MERGED -> {
                val drawings = "<div class=\"$prefix-old\">" + renderBlock(left, DiffStatus.REMOVED, side = "left") + "</div>" +
                    renderBlock(right, DiffStatus.ADDED)
                "<div class=\"mdr-item blk-$status $prefix-row\"$idx data-side=\"right\" data-line=\"${right.source.startLine}\">" +
                    source + "<div class=\"$prefix-pair\">" + quote(drawings, right.context) + "</div></div>\n"
            }
        }
    }

    /** An unchanged HTML comment is invisible, like in the rendered document. */
    private fun hidden(block: DiffBlock): String =
        if (block.status == DiffStatus.EQUAL && block.right?.kind == BlockKind.HTML_COMMENT) " hidden" else ""

    private fun leftMarks(block: DiffBlock) =
        if (block.status == DiffStatus.MODIFIED) InlineMarks.left(block.inline) else InlineMarks.NONE

    private fun rightMarks(block: DiffBlock, withRemoved: Boolean) =
        if (block.status == DiffStatus.MODIFIED) InlineMarks.right(block.inline, withRemoved, block.left!!.atoms) else InlineMarks.NONE

    /** Links and images of a modified block whose text is equal but whose target changed. */
    private fun linkChanges(block: DiffBlock): LinkChanges.Result =
        if (block.status == DiffStatus.MODIFIED) LinkChanges.between(block.left!!, block.right!!) else LinkChanges.Result.NONE

    private fun side(
        side: String,
        content: Block?,
        status: DiffStatus,
        marks: InlineMarks,
        note: String?,
        links: Map<Node, String>,
        /** HTML after the block, inside the side, when the side has content. */
        after: String = "",
    ): String {
        val open = "<div class=\"mdr-side mdr-$side\" data-side=\"$side\""
        if (content == null) return "$open><div class=\"mdr-placeholder\"></div></div>"
        val inner = renderBlock(content, status, marks, side, note, links)
        return "$open data-line=\"${content.source.startLine}\">" + quote(inner, content.context) + after + "</div>"
    }

    /** Wraps a block in its quotes; inside a GitHub alert the outermost quote carries the alert's classes. */
    private fun quote(html: String, ctx: BlockContext): String {
        val depth = ctx.quoteDepth
        if (depth == 0) return html
        val outer = if (ctx.alert != null) {
            val type = ctx.alert.lowercase()
            "<blockquote class=\"mdr-alert mdr-alert-${escape(type)}\">"
        } else "<blockquote>"
        return outer + "<blockquote>".repeat(depth - 1) + html + "</blockquote>".repeat(depth)
    }

    /**
     * Note for a modified block whose change the word diff does not show: a task checkbox state, an
     * alert type or a heading level. Shown on the new side.
     */
    private fun changeNote(block: DiffBlock): String? {
        val l = block.left ?: return null
        val r = block.right ?: return null
        if (block.status != DiffStatus.MODIFIED) return null
        return when (l.kind) {
            BlockKind.LIST_ITEM -> {
                val before = taskState(l)
                val after = taskState(r)
                if (before == null || after == null || before == after) null
                else taskLabel(before) + " → " + taskLabel(after)
            }
            BlockKind.ALERT -> if (l.plainText == r.plainText) null else l.plainText + " → " + r.plainText
            BlockKind.HEADING -> if (r.kind != BlockKind.HEADING || l.level == r.level) null else options.labels.headingLevel(l.level, r.level)
            else -> null
        } ?: hiddenChange(l, r)
    }

    /** A change only in HTML comments or markup, which the rendered text does not show. */
    private fun hiddenChange(l: Block, r: Block): String? {
        if (l.kind == BlockKind.HTML_COMMENT || l.kind in RAW_KINDS || l.plainText != r.plainText) return null
        return when {
            HtmlFragment.comments(l.rawText) != HtmlFragment.comments(r.rawText) -> options.labels.commentChanged
            l.kind == BlockKind.HTML || l.kind == BlockKind.DETAILS -> options.labels.markupChanged
            else -> null
        }
    }

    private fun taskLabel(checked: Boolean) = if (checked) options.labels.checked else options.labels.unchecked

    private fun taskState(block: Block): Boolean? = block.renderNodes.firstNotNullOfOrNull { node ->
        (node as? TaskListItemMarker ?: node.firstChild as? TaskListItemMarker)?.isChecked
    }

    private fun noteAttributes(note: String?): String =
        if (note == null) "" else " data-change-note=\"" + escape(note) + "\""

    private fun noteClass(note: String?): String = if (note == null) "" else " mdr-meta-changed"

    /** One block's own HTML, carrying `mdr-block` and its status class. */
    fun renderBlock(
        block: Block,
        status: DiffStatus = DiffStatus.EQUAL,
        marks: InlineMarks = InlineMarks.NONE,
        side: String = "right",
        note: String? = null,
        /** Links and images whose target changed, mapped to the other side's URL. */
        links: Map<Node, String> = emptyMap(),
    ): String {
        val cls = "mdr-block mdr-" + status.name.lowercase()
        return when (block.kind) {
            BlockKind.CODE_FENCE -> {
                val label = when {
                    block.key == BlockExtractor.FRONT_MATTER_FENCE_KEY -> options.labels.frontMatter
                    else -> block.plainText.ifEmpty { options.labels.code }
                }
                "<div class=\"$cls mdr-fence\">" + escape(label) + "</div>"
            }
            BlockKind.CODE_LINE, BlockKind.FRONT_MATTER -> {
                val writer = MarkedTextWriter(marks, ::escape)
                val text = writer.write(0, block.plainText) + writer.rest()
                val fm = if (block.kind == BlockKind.FRONT_MATTER) " mdr-fm-line" else ""
                "<div class=\"$cls mdr-codeline$fm\"><pre><code><span class=\"mdr-line mdr-${status.name.lowercase()}\">" +
                    text.ifEmpty { "​" } + "</span></code></pre></div>"
            }
            // Inert source for the page's diagram script, which draws it in place; shown as code without the script.
            BlockKind.MERMAID -> "<div class=\"$cls mdr-mermaid\"><pre class=\"mdr-mermaid-code\"><code>" +
                escape(block.plainText) + "</code></pre></div>"
            // Inert TeX source for the page's math script, which draws it in place; shown as monospace source without it.
            BlockKind.MATH -> "<div class=\"$cls mdr-math-block\"><div class=\"mdr-math mdr-math-display\">" +
                escape(block.plainText) + "</div></div>"
            BlockKind.HTML -> {
                val parts = HtmlFragment.parse(block.rawText, options.allowRemoteImages, openDetails = status != DiffStatus.EQUAL)
                "<div class=\"$cls mdr-html${noteClass(note)}\"${noteAttributes(note)}>" + markedHtml(parts, marks) + "</div>"
            }
            BlockKind.DETAILS -> {
                // The summary row of a `<details>` group: the element itself wraps the group (see renderRange).
                val parts = HtmlFragment.parse(block.rawText, options.allowRemoteImages)
                    .filterNot { it is HtmlFragment.Markup && (it.tag == "details" || it.tag == "summary") }
                val title = if (block.plainText.isBlank()) escape(options.labels.details) else markedHtml(parts, marks)
                "<div class=\"$cls mdr-details-title${noteClass(note)}\"${noteAttributes(note)}>" +
                    "<span class=\"mdr-details-marker\"></span>$title</div>"
            }
            BlockKind.HTML_COMMENT -> {
                val writer = MarkedTextWriter(marks, ::escape)
                val text = writer.write(0, block.plainText) + writer.rest()
                "<div class=\"$cls mdr-comment\" title=\"" + escape(options.labels.comment) + "\">" +
                    "<span class=\"mdr-comment-text\">$text</span></div>"
            }
            BlockKind.LIST_ITEM -> listItem(block, cls, marks, side, note, links)
            BlockKind.ALERT -> {
                val writer = MarkedTextWriter(marks, ::escape)
                val title = writer.write(0, block.plainText) + writer.rest()
                "<p class=\"$cls mdr-alert-title${noteClass(note)}\"${noteAttributes(note)}>$title</p>"
            }
            BlockKind.FOOTNOTE -> {
                val label = (block.node as FootnoteDefinition).label
                "<div class=\"$cls mdr-footnote\" id=\"${footnoteId(side, label)}\">" +
                    "<span class=\"mdr-fn-label\">" + escape(label) + "</span>" +
                    "<div class=\"mdr-fn-body\">" + renderNodes(block.renderNodes, marks, side, links) + "</div></div>"
            }
            BlockKind.TABLE_ROW -> {
                val row = block.node!!
                val cols = generateSequence(row.firstChild) { it.next }.count()
                val html = renderNodes(listOf(row), marks, side, links).replaceFirst("<tr>", "<tr class=\"$cls\">")
                "<table class=\"mdr-trow\" style=\"--cols:$cols\"><tbody>$html</tbody></table>"
            }
            else -> "<div class=\"$cls${noteClass(note)}\"${noteAttributes(note)}>" + renderNodes(block.renderNodes, marks, side, links) + "</div>"
        }
    }

    /** Sanitized HTML with word marks on its visible text. */
    private fun markedHtml(parts: List<HtmlFragment.Part>, marks: InlineMarks): String {
        val writer = MarkedTextWriter(marks, ::escape)
        var offset = 0
        val html = HtmlSanitizer.write(parts) { text -> writer.write(offset, text).also { offset += text.length } }
        return html + writer.rest()
    }

    private fun listItem(block: Block, cls: String, marks: InlineMarks, side: String, note: String?, links: Map<Node, String>): String {
        val ctx = block.context
        val tag = if (ctx.ordered) "ol" else "ul"
        val start = if (ctx.ordered && ctx.number != null) " start=\"${ctx.number}\"" else ""
        val task = if (hasTaskMarker(block)) " mdr-task" else ""
        val content = renderNodes(block.renderNodes, marks, side, links)
        return "<$tag class=\"mdr-li\" style=\"--depth:${ctx.listDepth - 1}\"$start>" +
            "<li class=\"$cls$task${noteClass(note)}\"${noteAttributes(note)}>$content</li></$tag>"
    }

    private fun hasTaskMarker(block: Block): Boolean =
        block.renderNodes.any { node -> node is TaskListItemMarker || node.firstChild is TaskListItemMarker }

    /**
     * Renders [nodes] one after another, with word marks applied. Plain-text offsets continue
     * across nodes with one separator character, matching how the extractor joins them.
     */
    private fun renderNodes(nodes: List<Node>, marks: InlineMarks, side: String, links: Map<Node, String>): String {
        val writer = MarkedTextWriter(marks, ::escape) { InlineMathRenderer.fromText(it, ::escape) }
        val tooltip = if (side == "left") options.labels.newUrl else options.labels.previousUrl
        val linkNotes = links.mapValuesTo(java.util.IdentityHashMap()) { tooltip(it.value) }
        val baseUri = if (side == "left") options.leftBaseUri else options.rightBaseUri
        val sb = StringBuilder()
        var base = 0
        for (node in nodes) {
            val plain = BlockExtractor.plainTextWithOffsets(node)
            val offsets = plain.offsets.mapValuesTo(java.util.IdentityHashMap()) { it.value + base }
            val renderer = HtmlRenderer.builder()
                .extensions(renderExtensions)
                .nodeRendererFactory { ctx -> MarkedTextNodeRenderer(ctx, writer, offsets) }
                .nodeRendererFactory { ctx -> FootnoteReferenceRenderer(ctx, writer, offsets) { footnoteId(side, it) } }
                .nodeRendererFactory { ctx -> HardBreakRenderer(ctx, writer, offsets) }
                .nodeRendererFactory { ctx -> InlineMathRenderer(ctx, writer, offsets, ::escape) }
                .nodeRendererFactory { ctx -> SanitizedHtmlRenderer(ctx, options.allowRemoteImages) }
                .attributeProviderFactory { LinkAttributeProvider(baseUri, linkNotes) }
                .sanitizeUrls(true)
                .build()
            sb.append(renderer.render(node))
            base += plain.text.length + 1
        }
        sb.append(writer.rest())
        return sb.toString()
    }

    companion object {
        /** Kinds rendered from their source, where comments are text. */
        private val RAW_KINDS = setOf(BlockKind.CODE_LINE, BlockKind.CODE_FENCE, BlockKind.FRONT_MATTER, BlockKind.MERMAID, BlockKind.MATH)

        /** Kinds drawn by the page script, whose changes show both drawings and a toggle to the source diff. */
        private val DRAWN_KINDS = setOf(BlockKind.MERMAID, BlockKind.MATH)

        /**
         * The parser's extensions minus footnotes: their renderer collects definitions at the end of the
         * document, while here each definition is a block of its own ([FootnoteReferenceRenderer]).
         */
        private val renderExtensions = BlockExtractor.extensions.filterNot { it is FootnotesExtension }

        /** Anchor of a footnote definition; per side, because both sides of the page define the same labels. */
        fun footnoteId(side: String, label: String): String =
            "mdr-fn-$side-" + label.replace(Regex("[^A-Za-z0-9_-]")) { "_" + it.value.first().code.toString(16) }

        fun escape(text: String): String {
            val sb = StringBuilder(text.length)
            for (c in text) {
                when (c) {
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '&' -> sb.append("&amp;")
                    '"' -> sb.append("&quot;")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
