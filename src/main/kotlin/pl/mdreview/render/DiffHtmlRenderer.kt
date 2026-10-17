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
import pl.mdreview.engine.MarkdownDiff

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
        val folds = foldRanges(diff.blocks)
        var k = 0
        while (k < diff.blocks.size) {
            val fold = folds[k]
            if (fold != null) {
                body.append("<details class=\"mdr-fold\"><summary>")
                    .append(escape(options.labels.unchangedBlocks(fold.last - fold.first + 1))).append("</summary>\n")
                for (f in fold) body.append(block(diff.blocks[f], f))
                body.append("</details>\n")
                k = fold.last + 1
                continue
            }
            body.append(block(diff.blocks[k], k))
            k++
        }
        body.append("</div>\n")
        return body.toString()
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

    /** Ranges of indices to fold, keyed by their first index. */
    private fun foldRanges(blocks: List<DiffBlock>): Map<Int, IntRange> {
        if (!options.collapseUnchanged) return emptyMap()
        val result = mutableMapOf<Int, IntRange>()
        var k = 0
        while (k < blocks.size) {
            if (blocks[k].isChange) { k++; continue }
            var end = k
            while (end + 1 < blocks.size && !blocks[end + 1].isChange) end++
            val atStart = k == 0
            val atEnd = end == blocks.size - 1
            val from = if (atStart) k else k + options.foldContext
            val to = if (atEnd) end else end - options.foldContext
            // A document without changes is shown whole.
            if (end - k + 1 > options.foldThreshold && to - from + 1 >= 2 && !(atStart && atEnd)) {
                result[from] = from..to
            }
            k = end + 1
        }
        return result
    }

    private fun block(block: DiffBlock, index: Int): String {
        val status = block.status.name.lowercase()
        val sb = StringBuilder()
        when (options.mode) {
            ViewMode.SIDE_BY_SIDE -> {
                sb.append("<div class=\"mdr-row blk-").append(status).append("\" data-idx=\"").append(index)
                    .append("\" data-block-id=\"b").append(index).append("\">")
                val links = linkChanges(block)
                sb.append(side("left", block.left, block.status, leftMarks(block), null, links.left))
                sb.append(side("right", block.right, block.status, rightMarks(block, withRemoved = false), changeNote(block), links.right))
                sb.append("</div>\n")
            }
            ViewMode.MERGED -> {
                val (content, side, marks) = when (block.status) {
                    DiffStatus.REMOVED -> Triple(block.left!!, "left", InlineMarks.NONE)
                    DiffStatus.MODIFIED -> Triple(block.right!!, "right", rightMarks(block, withRemoved = true))
                    else -> Triple(block.right!!, "right", InlineMarks.NONE)
                }
                sb.append("<div class=\"mdr-item blk-").append(status).append("\" data-idx=\"").append(index)
                    .append("\" data-block-id=\"b").append(index).append("\" data-side=\"").append(side)
                    .append("\" data-line=\"").append(content.source.startLine).append("\">")
                val note = if (block.status == DiffStatus.MODIFIED) changeNote(block) else null
                val links = if (side == "right") linkChanges(block).right else emptyMap()
                sb.append(quote(renderBlock(content, block.status, marks, side, note, links), content.context))
                sb.append("</div>\n")
            }
        }
        return sb.toString()
    }

    private fun leftMarks(block: DiffBlock) =
        if (block.status == DiffStatus.MODIFIED) InlineMarks.left(block.inline) else InlineMarks.NONE

    private fun rightMarks(block: DiffBlock, withRemoved: Boolean) =
        if (block.status == DiffStatus.MODIFIED) InlineMarks.right(block.inline, withRemoved) else InlineMarks.NONE

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
    ): String {
        val open = "<div class=\"mdr-side mdr-$side\" data-side=\"$side\""
        if (content == null) return "$open><div class=\"mdr-placeholder\"></div></div>"
        val inner = renderBlock(content, status, marks, side, note, links)
        return "$open data-line=\"${content.source.startLine}\">" + quote(inner, content.context) + "</div>"
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
            BlockKind.HTML -> "<div class=\"$cls\">" + HtmlSanitizer.sanitize(block.rawText, options.allowRemoteImages) + "</div>"
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
        val writer = MarkedTextWriter(marks, ::escape)
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
