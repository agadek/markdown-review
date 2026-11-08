package pl.mdreview.render

import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Node
import org.commonmark.renderer.html.HtmlRenderer
import pl.mdreview.api.ViewMode
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.BlockContext
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.Container
import pl.mdreview.engine.DiffBlock
import pl.mdreview.engine.DiffEngine
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.HtmlFragment
import pl.mdreview.engine.MarkdownBlockSource
import pl.mdreview.engine.MarkdownDiff
import pl.mdreview.engine.asciidoc.AsciiDocMath

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
    /** Source language change on a code fence; `null` is a fence without a language. */
    val sourceLanguage: (String?, String?) -> String = { from, to -> "${from ?: "source"} \u2192 ${to ?: "source"}" },
    /** Note for a block whose markup changed but whose visible text did not (for example `*word*` → `_word_`). */
    val formattingChanged: String = "formatting changed",
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
        val bounds = containerBounds(diff.blocks)
        renderRange(diff, 0, diff.blocks.size, body, bounds)
        body.append("</div>\n")
        return body.toString()
    }

    /**
     * Renders blocks [from] until [to]. A multi-block `<details>` element becomes a real collapsible element around
     * its rows, open when anything in it changed; its summary row is the [BlockKind.DETAILS] block.
     */
    private fun renderRange(diff: MarkdownDiff, from: Int, to: Int, out: StringBuilder, bounds: RenderBounds) {
        val blocks = diff.blocks
        var spanStart = from
        var k = from
        while (k < to) {
            val opener = detailsOpener(blocks[k])
            if (opener == null) {
                k++
                continue
            }
            renderSpan(diff, spanStart, k, out, bounds)
            var end = k + 1
            while (end < to && detailsDepth(blocks[end]) > opener.context.details) end++
            val open = (k until end).any { blocks[it].isChange }
            out.append("<details class=\"mdr-details\"").append(if (open) " open" else "")
                .append("><summary class=\"mdr-details-summary\">")
            out.append(block(blocks[k], k, bounds).trimEnd())
            out.append("</summary>\n")
            renderRange(diff, k + 1, end, out, bounds)
            out.append("</details>\n")
            k = end
            spanStart = end
        }
        renderSpan(diff, spanStart, to, out, bounds)
    }

    private fun detailsOpener(block: DiffBlock): Block? =
        listOfNotNull(block.left, block.right).firstOrNull { it.kind == BlockKind.DETAILS }

    private fun detailsDepth(block: DiffBlock): Int = maxOf(block.left?.context?.details ?: 0, block.right?.context?.details ?: 0)

    /** Blocks [from] until [to], none of which opens a `<details>`, with long unchanged runs folded. */
    private fun renderSpan(diff: MarkdownDiff, from: Int, to: Int, out: StringBuilder, bounds: RenderBounds) {
        val folds = if (diff.hasChanges) foldRanges(diff.blocks, from, to) else emptyMap()
        var k = from
        while (k < to) {
            val fold = folds[k]
            if (fold != null) {
                out.append("<details class=\"mdr-fold\"><summary>")
                    .append(escape(options.labels.unchangedBlocks(fold.last - fold.first + 1))).append("</summary>\n")
                for (f in fold) out.append(block(diff.blocks[f], f, bounds))
                out.append("</details>\n")
                k = fold.last + 1
                continue
            }
            out.append(block(diff.blocks[k], k, bounds))
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
    private fun block(block: DiffBlock, index: Int?, bounds: RenderBounds = RenderBounds.NONE): String {
        val status = block.status.name.lowercase()
        val idx = if (index == null) "" else " data-idx=\"$index\" data-block-id=\"b$index\"" + moveAttributes(block)
        if (isChangedDiagram(block)) return changedDiagram(block, status, idx)
        val moved = if (index != null && (block.movedFrom != null || block.movedTo != null)) " mdr-moved" else ""
        val moveNote = if (moved.isEmpty()) "" else moveNote(block, index!!)
        val links = linkChanges(block)
        val sb = StringBuilder()
        when (options.mode) {
            ViewMode.SIDE_BY_SIDE -> {
                sb.append("<div class=\"mdr-row blk-").append(status).append(moved).append("\"").append(idx).append(hidden(block)).append(">")
                sb.append(
                    side(
                        "left", block.left, block.status, leftMarks(block), null, links, moveNote,
                        bounds = if (index == null) ContainerBounds.NONE else bounds.left[index],
                    ),
                )
                sb.append(
                    side(
                        "right", block.right, block.status, rightMarks(block, withRemoved = false), changeNote(block, links), links, moveNote,
                        bounds = if (index == null) ContainerBounds.NONE else bounds.right[index],
                    ),
                )
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
                val note = if (block.status == DiffStatus.MODIFIED) changeNote(block, links) else null
                val contentLinks = if (side == "right") links else LinkChanges.Result.NONE
                sb.append(
                    frame(
                        quote(
                            renderBlock(content, block.status, marks, side, note, contentLinks.nodeLinks(side), contentLinks.htmlLinks(side)),
                            content.context,
                        ),
                        content,
                        if (index == null) ContainerBounds.NONE else bounds.merged[index],
                    ),
                )
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
        val lines = DiffEngine().diff(left.lines, right.lines).blocks
        val source = "<details class=\"$prefix-source\"><summary>" + escape(options.labels.showSourceChanges) +
            "</summary><div class=\"$prefix-lines\">" + lines.joinToString("") { block(it, null) } + "</div></details>"
        return when (options.mode) {
            ViewMode.SIDE_BY_SIDE -> "<div class=\"mdr-row blk-$status $prefix-row\"$idx>" + source +
                side("left", left, block.status, InlineMarks.NONE, null, LinkChanges.Result.NONE) +
                side("right", right, block.status, InlineMarks.NONE, null, LinkChanges.Result.NONE) + "</div>\n"
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
        links: LinkChanges.Result,
        /** HTML after the block, inside the side, when the side has content. */
        after: String = "",
        bounds: ContainerBounds = ContainerBounds.NONE,
    ): String {
        val open = "<div class=\"mdr-side mdr-$side\" data-side=\"$side\""
        if (content == null) return "$open><div class=\"mdr-placeholder\"></div></div>"
        val inner = renderBlock(content, status, marks, side, note, links.nodeLinks(side), links.htmlLinks(side))
        return "$open data-line=\"${content.source.startLine}\">" + frame(quote(inner, content.context), content, bounds) + after + "</div>"
    }

    /** Wraps a block in its quotes; inside a GitHub alert the outermost quote carries the alert's classes. */
    private fun quote(html: String, ctx: BlockContext): String {
        val depth = ctx.quoteDepth
        if (depth == 0) return html
        val outer = if (ctx.alert != null && ctx.quoteDepth > 0 && ctx.containers.none { it.kind == ADMONITION }) {
            val type = ctx.alert.lowercase()
            "<blockquote class=\"mdr-alert mdr-alert-${escape(type)}\">"
        } else "<blockquote>"
        return outer + "<blockquote>".repeat(depth - 1) + html + "</blockquote>".repeat(depth)
    }

    /**
     * Wraps a block in the frames of its AsciiDoc containers, outermost first. A container title is drawn as a block
     * title on the container's first row; a `BLOCK_TITLE` row already carries it, so there is no second title then.
     */
    private fun frame(html: String, block: Block, bounds: ContainerBounds): String {
        val containers = block.context.containers
        if (containers.isEmpty()) {
            // An admonition paragraph is one ALERT block: its type may live in context.alert instead of a container.
            if (block.kind == BlockKind.ALERT && block.content is BlockContent.Html && block.context.alert != null) {
                return admonitionFrame(html, block.context.alert, first = true, last = true)
            }
            return html
        }
        var result = html
        for (i in containers.indices.reversed()) {
            val container = containers[i]
            if (container.kind !in FRAME_CONTAINERS) continue
            if (container.kind == ADMONITION) {
                result = admonitionFrame(result, container.type, bounds.isFirst(i), bounds.isLast(i))
                continue
            }
            val classes = StringBuilder("mdr-container mdr-container-").append(containerClass(container.kind))
            if (bounds.isFirst(i)) classes.append(" mdr-container-first")
            if (bounds.isLast(i)) classes.append(" mdr-container-last")
            val title = if (bounds.isFirst(i) && !bounds.isTitleSuppressed(i) && container.title != null &&
                block.kind != BlockKind.BLOCK_TITLE
            ) {
                containerTitle(container.title)
            } else {
                ""
            }
            result = "<div class=\"$classes\">$title$result</div>"
        }
        return result
    }

    private fun admonitionFrame(html: String, type: String?, first: Boolean, last: Boolean): String {
        val classes = StringBuilder("mdr-container mdr-container-admonition")
        val alert = type?.lowercase()?.takeIf { it in ALERT_TYPES }
        if (alert != null) classes.append(" mdr-alert mdr-alert-").append(alert)
        if (first) classes.append(" mdr-container-first")
        if (last) classes.append(" mdr-container-last")
        return "<div class=\"$classes\">$html</div>"
    }

    private fun containerTitle(title: String): String {
        val parts = htmlParts(HtmlFragment.parse(title, allowRemoteImages = true), DiffStatus.EQUAL)
        return "<div class=\"mdr-block-title\">" + markedHtml(parts, InlineMarks.NONE) + "</div>"
    }

    private fun containerClass(kind: String): String =
        kind.lowercase().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("")

    private fun containerBounds(blocks: List<DiffBlock>): RenderBounds {
        val left = sideBounds(blocks) { it.left }
        val right = sideBounds(blocks) { it.right }
        val merged = sideBounds(blocks) { block ->
            when (block.status) {
                DiffStatus.REMOVED -> block.left
                else -> block.right
            }
        }
        return RenderBounds(left, right, merged)
    }

    /**
     * First/last container flags per block on one side, comparing with the adjacent rows. A row without content on
     * that side (a placeholder) ends the frame: a box that disappears for a row starts again below it.
     */
    private fun sideBounds(blocks: List<DiffBlock>, content: (DiffBlock) -> Block?): List<ContainerBounds> {
        val previous = List<List<Container>?>(blocks.size) { i ->
            if (i == 0) null else content(blocks[i - 1])?.context?.containers
        }
        val next = List<List<Container>?>(blocks.size) { i ->
            if (i == blocks.size - 1) null else content(blocks[i + 1])?.context?.containers
        }
        return blocks.indices.map { i ->
            val block = content(blocks[i]) ?: return@map ContainerBounds.NONE
            val containers = block.context.containers
            val previousBlock = if (i == 0) null else content(blocks[i - 1])
            ContainerBounds(
                BooleanArray(containers.size) { depth -> previous[i]?.getOrNull(depth) != containers[depth] },
                BooleanArray(containers.size) { depth -> next[i]?.getOrNull(depth) != containers[depth] },
                BooleanArray(containers.size) { depth ->
                    previousBlock?.kind == BlockKind.BLOCK_TITLE &&
                        containers[depth].title?.let { HtmlFragment.visibleText(it).trim() } == previousBlock.plainText.trim()
                },
            )
        }
    }

    private class ContainerBounds(
        val first: BooleanArray,
        val last: BooleanArray,
        /** The previous row is a BLOCK_TITLE with this container's title, so the frame does not draw it again. */
        val suppressedTitle: BooleanArray = BooleanArray(0),
    ) {
        fun isFirst(depth: Int): Boolean = depth < first.size && first[depth]
        fun isLast(depth: Int): Boolean = depth < last.size && last[depth]
        fun isTitleSuppressed(depth: Int): Boolean = depth < suppressedTitle.size && suppressedTitle[depth]

        companion object {
            val NONE = ContainerBounds(BooleanArray(0), BooleanArray(0))
        }
    }

    private class RenderBounds(
        val left: List<ContainerBounds>,
        val right: List<ContainerBounds>,
        val merged: List<ContainerBounds>,
    ) {
        companion object {
            val NONE = RenderBounds(emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * Note for a modified block whose change the word diff does not show: a task checkbox state, an
     * alert type, a heading level, a source language or a container type. Shown on the new side.
     */
    private fun changeNote(block: DiffBlock, links: LinkChanges.Result = LinkChanges.Result.NONE): String? {
        val l = block.left ?: return null
        val r = block.right ?: return null
        if (block.status != DiffStatus.MODIFIED) return null
        val explicit = when (l.kind) {
            BlockKind.LIST_ITEM -> {
                val before = taskState(l)
                val after = taskState(r)
                if (before == null || after == null || before == after) null
                else taskLabel(before) + " → " + taskLabel(after)
            }
            BlockKind.ALERT -> {
                if (l.content is BlockContent.Html && r.content is BlockContent.Html) {
                    // An AsciiDoc admonition: the body is a word diff, only the type needs a note.
                    val before = alertType(l)
                    val after = alertType(r)
                    if (before == null || after == null || before.equals(after, ignoreCase = true)) null
                    else alertTitle(before) + " → " + alertTitle(after)
                } else if (l.plainText == r.plainText) {
                    null
                } else {
                    l.plainText + " → " + r.plainText
                }
            }
            BlockKind.HEADING -> if (r.kind != BlockKind.HEADING || l.level == r.level) null else options.labels.headingLevel(l.level, r.level)
            BlockKind.CODE_FENCE -> {
                if (l.language == r.language || !isAsciiDocFence(l) || !isAsciiDocFence(r)) null
                else options.labels.sourceLanguage(l.language, r.language)
            }
            else -> null
        }
        return explicit ?: containerChangeNote(l, r) ?: hiddenChange(l, r) ?: formattingChange(block, links)
    }

    /** A changed admonition container type, shown on the blocks inside it. */
    private fun containerChangeNote(l: Block, r: Block): String? {
        val before = l.context.containers.lastOrNull { it.kind == ADMONITION }?.type ?: return null
        val after = r.context.containers.lastOrNull { it.kind == ADMONITION }?.type ?: return null
        if (before.equals(after, ignoreCase = true)) return null
        return alertTitle(before) + " → " + alertTitle(after)
    }

    private fun alertTitle(type: String): String = MarkdownBlockSource.alertTitle(type.uppercase())

    /** Type of an AsciiDoc admonition, from its container or from the alert context a paragraph gets. */
    private fun alertType(block: Block): String? =
        block.context.containers.lastOrNull { it.kind == ADMONITION }?.type ?: block.context.alert

    /**
     * A block whose markup changed but whose visible text did not, for example `*word*` to `_word_`. Only AsciiDoc
     * blocks with sanitized inline content can report this: Markdown has its own notes and no source for the styles.
     */
    private fun formattingChange(block: DiffBlock, links: LinkChanges.Result): String? {
        val l = block.left ?: return null
        val r = block.right ?: return null
        if (l.kind != r.kind || l.kind !in FORMATTING_KINDS) return null
        if (l.content !is BlockContent.Html || r.content !is BlockContent.Html) return null
        if (block.inline.any { it.status != DiffStatus.EQUAL }) return null
        if (l.rawText == r.rawText) return null
        if (links.leftHtml.isNotEmpty() || links.rightHtml.isNotEmpty() || links.left.isNotEmpty() || links.right.isNotEmpty()) return null
        return options.labels.formattingChanged
    }

    /** AsciiDoc code fences come from `[source,...]` or `----` / `....` delimiters, never from a backtick fence. */
    private fun isAsciiDocFence(block: Block): Boolean {
        val raw = block.rawText.trimStart()
        return raw.startsWith("[") || raw.startsWith("----") || raw.startsWith("....")
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

    private fun taskState(block: Block): Boolean? {
        val node = block.nodes.firstNotNullOfOrNull { node ->
            (node as? TaskListItemMarker ?: node.firstChild as? TaskListItemMarker)?.isChecked
        }
        if (node != null) return node
        // AsciiDoc checklist items carry their state in the source marker; the converted inline HTML does not.
        val marker = asciiDocCheckbox.find(block.rawText) ?: return null
        return marker.groupValues[1] != " "
    }

    private fun taskCheckbox(checked: Boolean): String =
        "<input type=\"checkbox\" disabled=\"\"" + (if (checked) " checked=\"\"" else "") + " /> "

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
        /** HTML links and images whose target changed, by their occurrence in [BlockContent.Html] parts. */
        htmlLinks: Map<Int, String> = emptyMap(),
    ): String {
        val cls = "mdr-block mdr-" + status.name.lowercase()
        val content = block.content
        val asciiDocLayout = when {
            content is BlockContent.Commonmark -> false
            block.kind in ASCIIDOC_ONLY_KINDS -> true
            block.kind in ASCIIDOC_HTML_KINDS && content is BlockContent.Html -> true
            block.kind == BlockKind.THEMATIC_BREAK && content is BlockContent.Raw -> true
            else -> false
        }
        if (asciiDocLayout) {
            val parts = (content as? BlockContent.Html)?.parts ?: listOf(HtmlFragment.TextRun(block.plainText))
            return renderAsciiDocBlock(block, parts, cls, status, marks, side, note, htmlLinks)
        }
        return when (content) {
            is BlockContent.Html -> renderHtmlBlock(block, content.parts, cls, status, marks, side, note, htmlLinks)
            BlockContent.Raw -> renderRawBlock(block, cls, status, marks, note)
            is BlockContent.Commonmark -> renderCommonmarkBlock(block, cls, marks, side, note, links)
        }
    }

    /** Markup for an AsciiDoc block; only its inline content comes from [BlockContent.Html]. */
    private fun renderAsciiDocBlock(
        block: Block,
        rawParts: List<HtmlFragment.Part>,
        cls: String,
        status: DiffStatus,
        marks: InlineMarks,
        side: String,
        note: String?,
        htmlLinks: Map<Int, String>,
    ): String {
        val parts = routeFootnoteReferences(resolveImageSources(htmlParts(rawParts, status), side), side)
        return when (block.kind) {
            BlockKind.HEADING -> {
                val level = block.level.coerceIn(1, 6)
                val inline = markedHtml(stripOuterTag(parts, "h$level"), marks, htmlLinks, side)
                "<div class=\"$cls${noteClass(note)}\"${noteAttributes(note)}><h$level>$inline</h$level></div>"
            }
            BlockKind.PARAGRAPH -> {
                val inline = markedHtml(stripOuterTag(parts, "p"), marks, htmlLinks, side)
                "<div class=\"$cls${noteClass(note)}\"${noteAttributes(note)}><p>$inline</p></div>"
            }
            BlockKind.LIST_ITEM -> asciiDocListItem(block, parts, cls, marks, side, note, htmlLinks)
            BlockKind.DESCRIPTION_TERM -> {
                val inline = markedHtml(parts, marks, htmlLinks, side)
                "<dl class=\"mdr-dlist\"><dt class=\"$cls${noteClass(note)}\"${noteAttributes(note)}>$inline</dt></dl>"
            }
            BlockKind.TABLE_ROW -> asciiDocTableRow(parts, cls, note, marks, side, htmlLinks)
            BlockKind.ALERT -> {
                // An AsciiDoc admonition paragraph: one block holding the type title and the body.
                val inline = markedHtml(parts, marks, htmlLinks, side)
                val title = alertType(block)?.let { "<p class=\"mdr-alert-title\">" + escape(alertTitle(it)) + "</p>" }.orEmpty()
                "<div class=\"$cls${noteClass(note)}\"${noteAttributes(note)}>$title" +
                    "<div class=\"mdr-alert-body\">$inline</div></div>"
            }
            BlockKind.BLOCK_TITLE -> {
                val inline = markedHtml(parts, marks, htmlLinks, side)
                "<div class=\"$cls mdr-block-title${noteClass(note)}\"${noteAttributes(note)}>$inline</div>"
            }
            BlockKind.IMAGE -> asciiDocImage(block, parts, cls, status, marks, side, note, htmlLinks)
            BlockKind.ATTRIBUTE -> {
                val inline = markedHtml(parts, marks, htmlLinks, side)
                "<div class=\"$cls mdr-attribute${noteClass(note)}\"${noteAttributes(note)}>$inline</div>"
            }
            BlockKind.INCLUDE -> asciiDocInclude(block, cls, marks, side, note, htmlLinks)
            BlockKind.FOOTNOTE -> {
                val label = block.label.orEmpty()
                val inline = markedHtml(parts, marks, htmlLinks, side)
                "<div class=\"$cls mdr-footnote${noteClass(note)}\" id=\"${footnoteId(side, label)}\"${noteAttributes(note)}>" +
                    "<span class=\"mdr-fn-label\">" + escape(label) + "</span>" +
                    "<div class=\"mdr-fn-body\">$inline</div></div>"
            }
            BlockKind.THEMATIC_BREAK -> asciiDocBreak(block, cls, note)
            else -> {
                val inline = markedHtml(parts, marks, htmlLinks, side)
                "<div class=\"$cls mdr-html${noteClass(note)}\"${noteAttributes(note)}>$inline</div>"
            }
        }
    }

    /** An AsciiDoc list item, or the description of a description-list entry. */
    private fun asciiDocListItem(
        block: Block,
        parts: List<HtmlFragment.Part>,
        cls: String,
        marks: InlineMarks,
        side: String,
        note: String?,
        htmlLinks: Map<Int, String>,
    ): String {
        val ctx = block.context
        val state = taskState(block)
        val task = if (state != null) " mdr-task" else ""
        val inline = (if (state == null) "" else taskCheckbox(state)) + markedHtml(parts, marks, htmlLinks, side)
        if (ctx.containers.any { it.kind == DESCRIPTION }) {
            return "<dl class=\"mdr-dlist\"><dd class=\"$cls$task${noteClass(note)}\"${noteAttributes(note)}>$inline</dd></dl>"
        }
        val callout = ctx.containers.any { it.kind == CALLOUT }
        val tag = if (ctx.ordered || callout) "ol" else "ul"
        val listClass = if (callout) "mdr-li mdr-callout" else "mdr-li"
        val start = if ((ctx.ordered || callout) && ctx.number != null) " start=\"${ctx.number}\"" else ""
        return "<$tag class=\"$listClass\" style=\"--depth:${ctx.listDepth - 1}\"$start>" +
            "<li class=\"$cls$task${noteClass(note)}\"${noteAttributes(note)}>$inline</li></$tag>"
    }

    private fun asciiDocTableRow(
        parts: List<HtmlFragment.Part>,
        cls: String,
        note: String?,
        marks: InlineMarks,
        side: String,
        htmlLinks: Map<Int, String>,
    ): String {
        val cols = parts.count { it is HtmlFragment.Markup && !it.closing && (it.tag == "td" || it.tag == "th") }.coerceAtLeast(1)
        val inline = markedHtml(parts, marks, htmlLinks, side, cellGap = 3)
        return "<table class=\"mdr-trow\" style=\"--cols:$cols\"><tbody><tr class=\"$cls${noteClass(note)}\"${noteAttributes(note)}>$inline</tr></tbody></table>"
    }

    /**
     * A block image through the Markdown image path: the target resolves against the side's base URI, the remote-image
     * policy applies, and a changed target gets `mdr-img-changed` with the other side's URL as the title.
     */
    private fun asciiDocImage(
        block: Block,
        parts: List<HtmlFragment.Part>,
        cls: String,
        status: DiffStatus,
        marks: InlineMarks,
        side: String,
        note: String?,
        htmlLinks: Map<Int, String>,
    ): String {
        val imageParts = if (parts.any { it is HtmlFragment.Markup && it.tag == "img" && !it.closing }) {
            resolveImageSources(parts, side)
        } else {
            val target = block.target.orEmpty()
            resolveImageSources(
                HtmlFragment.parse(
                    "<img src=\"" + escape(target) + "\" alt=\"" + escape(block.plainText) + "\">",
                    allowRemoteImages = true,
                ),
                side,
            )
        }
        val inline = markedHtml(htmlParts(imageParts, status), marks, htmlLinks, side)
        val caption = block.title?.takeIf { it.isNotBlank() }?.let { "<figcaption>" + escape(it) + "</figcaption>" }.orEmpty()
        return "<figure class=\"$cls mdr-image${noteClass(note)}\"${noteAttributes(note)}>$inline$caption</figure>"
    }

    private fun resolveImageSources(parts: List<HtmlFragment.Part>, side: String): List<HtmlFragment.Part> {
        val base = (if (side == "left") options.leftBaseUri else options.rightBaseUri) ?: return parts
        return parts.map { part ->
            if (part !is HtmlFragment.Markup || part.closing || part.tag != "img") return@map part
            val src = HtmlFragment.attribute(part, "src") ?: return@map part
            val resolved = LinkAttributeProvider.resolve(base, src) ?: return@map part
            HtmlFragment.withAttribute(part, "src", resolved)
        }
    }

    /** `include::target[]` with the target linked and resolved against the side's base URI. */
    private fun asciiDocInclude(
        block: Block,
        cls: String,
        marks: InlineMarks,
        side: String,
        note: String?,
        htmlLinks: Map<Int, String>,
    ): String {
        val target = block.target.orEmpty()
        val text = block.rawText.ifBlank { "include::$target[]" }
        val start = text.indexOf(target).takeIf { it >= 0 } ?: 0
        val end = start + target.length
        val base = if (side == "left") options.leftBaseUri else options.rightBaseUri
        val href = base?.let { LinkAttributeProvider.resolve(it, target) } ?: target
        val safe = HtmlFragment.safeUrl(target) && HtmlFragment.safeUrl(href)
        val other = htmlLinks[0]
        val tooltip = if (side == "left") options.labels.newUrl else options.labels.previousUrl
        val changed = if (other == null || !safe) "" else " class=\"mdr-link-changed\" title=\"" + escape(tooltip(other)) + "\""
        val writer = MarkedTextWriter(marks, ::escape)
        val targetHtml = writer.write(start, target)
        val link = if (safe) "<a href=\"" + escape(href) + "\"$changed>$targetHtml</a>" else targetHtml
        val html = writer.write(0, text.substring(0, start)) + link +
            writer.write(end, text.substring(end)) + writer.rest()
        return "<div class=\"$cls mdr-include${noteClass(note)}\"${noteAttributes(note)}>$html</div>"
    }

    private fun asciiDocBreak(block: Block, cls: String, note: String?): String {
        val page = block.rawText.trim() == "<<<"
        val hr = if (page) "<hr class=\"mdr-page-break\" />" else "<hr />"
        return "<div class=\"$cls${noteClass(note)}\"${noteAttributes(note)}>$hr</div>"
    }

    /**
     * Footnotes inside Asciidoctor's inline HTML link to Asciidoctor's own anchors; point them at the Markdown
     * footnote anchor on the same side so references and definitions meet.
     */
    private fun routeFootnoteReferences(parts: List<HtmlFragment.Part>, side: String): List<HtmlFragment.Part> {
        var inFootnote = false
        return parts.map { part ->
            if (part !is HtmlFragment.Markup) return@map part
            when {
                part.tag == "sup" && !part.closing -> {
                    inFootnote = HtmlFragment.attribute(part, "class")?.split(' ')?.contains("footnote") == true
                    part
                }
                part.tag == "sup" && part.closing -> {
                    inFootnote = false
                    part
                }
                inFootnote && part.tag == "a" && !part.closing -> {
                    val href = HtmlFragment.attribute(part, "href") ?: return@map part
                    if (!href.startsWith("#")) return@map part
                    val label = footnoteLabel(href.removePrefix("#"))
                    HtmlFragment.withAttribute(part, "href", "#" + footnoteId(side, label))
                }
                else -> part
            }
        }
    }

    private fun footnoteLabel(fragment: String): String =
        fragment.removePrefix("_footnoteref_").removePrefix("_footnote_").removePrefix("footnoteref_").removePrefix("footnote_")

    /** The parts without a single wrapping element, so the block element is written once, not twice. */
    private fun stripOuterTag(parts: List<HtmlFragment.Part>, tag: String): List<HtmlFragment.Part> {
        if (parts.size < 2) return parts
        val first = parts.first() as? HtmlFragment.Markup ?: return parts
        val last = parts.last() as? HtmlFragment.Markup ?: return parts
        if (first.closing || first.tag != tag || !last.closing || last.tag != tag) return parts
        val opens = parts.count { it is HtmlFragment.Markup && it.tag == tag && !it.closing }
        val closes = parts.count { it is HtmlFragment.Markup && it.tag == tag && it.closing }
        return if (opens == 1 && closes == 1) parts.subList(1, parts.size - 1) else parts
    }

    /** Today's HTML-block path, generalized to any block whose content is sanitized HTML. */
    private fun renderHtmlBlock(
        block: Block,
        parts: List<HtmlFragment.Part>,
        cls: String,
        status: DiffStatus,
        marks: InlineMarks,
        side: String,
        note: String?,
        htmlLinks: Map<Int, String>,
    ): String {
        val rendered = htmlParts(parts, status)
        return when (block.kind) {
            BlockKind.HTML_COMMENT -> commentBlock(block, cls, marks)
            BlockKind.DETAILS -> {
                // The summary row of a `<details>` group: the element itself wraps the group (see renderRange).
                val title = rendered.filterNot { it is HtmlFragment.Markup && (it.tag == "details" || it.tag == "summary") }
                val html = if (block.plainText.isBlank()) escape(options.labels.details) else markedHtml(title, marks, htmlLinks, side)
                "<div class=\"$cls mdr-details-title${noteClass(note)}\"${noteAttributes(note)}>" +
                    "<span class=\"mdr-details-marker\"></span>$html</div>"
            }
            else -> "<div class=\"$cls mdr-html${noteClass(note)}\"${noteAttributes(note)}>" +
                markedHtml(rendered, marks, htmlLinks, side) + "</div>"
        }
    }

    /**
     * [parts] with the renderer's remote-image policy and, for a changed block, the `open` attribute of a
     * `<details>` element added, exactly as [HtmlFragment.parse] would produce them.
     */
    private fun htmlParts(parts: List<HtmlFragment.Part>, status: DiffStatus): List<HtmlFragment.Part> {
        var result = if (options.allowRemoteImages) parts else HtmlFragment.withoutRemoteImages(parts)
        if (status != DiffStatus.EQUAL) result = HtmlFragment.withOpenDetails(result)
        return result
    }

    private fun renderRawBlock(block: Block, cls: String, status: DiffStatus, marks: InlineMarks, note: String?): String =
        when (block.kind) {
            BlockKind.CODE_FENCE -> {
                val label = when {
                    block.key == MarkdownBlockSource.FRONT_MATTER_FENCE_KEY -> options.labels.frontMatter
                    isAsciiDocFence(block) && block.language != null -> block.language!!
                    block.plainText.isNotEmpty() -> block.plainText
                    else -> block.language ?: options.labels.code
                }
                "<div class=\"$cls mdr-fence${noteClass(note)}\"${noteAttributes(note)}>" + escape(label) + "</div>"
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
            BlockKind.MATH -> {
                val asciiMath = block.language.equals(AsciiDocMath.ASCIIMATH, ignoreCase = true)
                val failed = if (asciiMath) " mdr-math-failed" else ""
                val title = if (asciiMath) " title=\"" + escape(AsciiDocMath.ASCIIMATH_UNSUPPORTED) + "\"" else ""
                val error = if (asciiMath) "<div class=\"mdr-math-error\">" + escape(AsciiDocMath.ASCIIMATH_UNSUPPORTED) + "</div>" else ""
                "<div class=\"$cls mdr-math-block\"><div class=\"mdr-math mdr-math-display$failed\"$title>" +
                    escape(block.plainText) + "</div>$error</div>"
            }
            BlockKind.HTML_COMMENT -> commentBlock(block, cls, marks)
            // The raw-text fallback for a format without a front end.
            else -> "<div class=\"$cls${noteClass(note)}\"${noteAttributes(note)}><pre>" + escape(block.plainText) + "</pre></div>"
        }

    private fun renderCommonmarkBlock(
        block: Block,
        cls: String,
        marks: InlineMarks,
        side: String,
        note: String?,
        links: Map<Node, String>,
    ): String = when (block.kind) {
        BlockKind.LIST_ITEM -> listItem(block, cls, marks, side, note, links)
        BlockKind.ALERT -> {
            val writer = MarkedTextWriter(marks, ::escape)
            val title = writer.write(0, block.plainText) + writer.rest()
            "<p class=\"$cls mdr-alert-title${noteClass(note)}\"${noteAttributes(note)}>$title</p>"
        }
        BlockKind.FOOTNOTE -> {
            val label = block.label.orEmpty()
            "<div class=\"$cls mdr-footnote\" id=\"${footnoteId(side, label)}\">" +
                "<span class=\"mdr-fn-label\">" + escape(label) + "</span>" +
                "<div class=\"mdr-fn-body\">" + renderNodes(block.nodes, marks, side, links) + "</div></div>"
        }
        BlockKind.TABLE_ROW -> {
            val row = block.nodes.first()
            val cols = generateSequence(row.firstChild) { it.next }.count()
            val html = renderNodes(block.nodes, marks, side, links).replaceFirst("<tr>", "<tr class=\"$cls\">")
            "<table class=\"mdr-trow\" style=\"--cols:$cols\"><tbody>$html</tbody></table>"
        }
        else -> "<div class=\"$cls${noteClass(note)}\"${noteAttributes(note)}>" + renderNodes(block.nodes, marks, side, links) + "</div>"
    }

    private fun commentBlock(block: Block, cls: String, marks: InlineMarks): String {
        val writer = MarkedTextWriter(marks, ::escape)
        val text = writer.write(0, block.plainText) + writer.rest()
        return "<div class=\"$cls mdr-comment\" title=\"" + escape(options.labels.comment) + "\">" +
            "<span class=\"mdr-comment-text\">$text</span></div>"
    }

    /** Sanitized HTML with word marks on its visible text and marks on changed link and image targets. */
    private fun markedHtml(
        parts: List<HtmlFragment.Part>,
        marks: InlineMarks,
        links: Map<Int, String> = emptyMap(),
        side: String = "right",
        /** Characters the block's plain text puts between table cells, which the cell markup does not contain. */
        cellGap: Int = 0,
    ): String {
        val writer = MarkedTextWriter(marks, ::escape)
        val tooltip = if (side == "left") options.labels.newUrl else options.labels.previousUrl
        var offset = 0
        var occurrence = 0
        val html = buildString {
            var index = 0
            while (index < parts.size) {
                val part = parts[index]
                if (part is HtmlFragment.Markup && isDrawnMathSpan(part)) {
                    val close = mathSpanClose(parts, index)
                    if (close != null) {
                        val inner = parts.subList(index + 1, close).filterIsInstance<HtmlFragment.TextRun>()
                        val text = inner.joinToString("") { it.text }
                        val span = part.html + escape(text) + (parts[close] as HtmlFragment.Markup).html
                        append(writer.atom(offset, text.length, span))
                        offset += text.length
                        index = close + 1
                        continue
                    }
                }
                when (part) {
                    is HtmlFragment.Markup -> {
                        var markup = part.html
                        if (!part.closing && (part.tag == "a" || part.tag == "img")) {
                            links[occurrence++]?.let { markup = changedLink(part, it, tooltip) }
                        }
                        append(markup)
                        if (cellGap > 0 && part.closing && (part.tag == "td" || part.tag == "th") &&
                            parts.drop(index + 1).any { it is HtmlFragment.Markup && !it.closing && (it.tag == "td" || it.tag == "th") }
                        ) {
                            offset += cellGap
                        }
                    }
                    is HtmlFragment.TextRun -> {
                        append(writer.write(offset, part.text))
                        offset += part.text.length
                    }
                }
                index++
            }
        }
        return html + writer.rest()
    }

    /** A drawn inline formula span written by [AsciiDocMath]: a whole atom for the word diff. */
    private fun isDrawnMathSpan(part: HtmlFragment.Markup): Boolean {
        if (part.tag != "span" || part.closing) return false
        val classes = HtmlFragment.attribute(part, "class")?.split(' ').orEmpty()
        return "mdr-math" in classes && "mdr-math-inline" in classes && "mdr-math-failed" !in classes
    }

    /** Index of the closing span of an inline formula, when its content is only text as the front end writes it. */
    private fun mathSpanClose(parts: List<HtmlFragment.Part>, open: Int): Int? {
        var index = open + 1
        while (index < parts.size) {
            val part = parts[index]
            if (part is HtmlFragment.Markup && part.closing && part.tag == "span") return index
            if (part !is HtmlFragment.TextRun) return null
            index++
        }
        return null
    }

    private fun changedLink(part: HtmlFragment.Markup, url: String, tooltip: (String) -> String): String {
        val cls = if (part.tag == "img") "mdr-img-changed" else "mdr-link-changed"
        val classValue = HtmlFragment.attribute(part, "class").let { if (it.isNullOrEmpty()) cls else "$it $cls" }
        val noted = HtmlFragment.withAttribute(part, "class", classValue)
        val title = HtmlFragment.attribute(noted, "title").let { if (it.isNullOrEmpty()) tooltip(url) else "$it\n${tooltip(url)}" }
        return HtmlFragment.withAttribute(noted, "title", title).html
    }

    private fun listItem(block: Block, cls: String, marks: InlineMarks, side: String, note: String?, links: Map<Node, String>): String {
        val ctx = block.context
        val tag = if (ctx.ordered) "ol" else "ul"
        val start = if (ctx.ordered && ctx.number != null) " start=\"${ctx.number}\"" else ""
        val task = if (hasTaskMarker(block)) " mdr-task" else ""
        val content = renderNodes(block.nodes, marks, side, links)
        return "<$tag class=\"mdr-li\" style=\"--depth:${ctx.listDepth - 1}\"$start>" +
            "<li class=\"$cls$task${noteClass(note)}\"${noteAttributes(note)}>$content</li></$tag>"
    }

    private fun hasTaskMarker(block: Block): Boolean =
        block.nodes.any { node -> node is TaskListItemMarker || node.firstChild is TaskListItemMarker }

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
            val plain = MarkdownBlockSource.plainTextWithOffsets(node)
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
        private const val DESCRIPTION = "description"
        private const val CALLOUT = "callout"
        private const val ADMONITION = "admonition"

        private val FRAME_CONTAINERS = setOf("example", "sidebar", "quote", "verse", "open", ADMONITION)
        private val ALERT_TYPES = setOf("note", "tip", "important", "warning", "caution")

        /** AsciiDoc-only kinds; they always use the AsciiDoc block markup. */
        private val ASCIIDOC_ONLY_KINDS = setOf(
            BlockKind.DESCRIPTION_TERM,
            BlockKind.BLOCK_TITLE,
            BlockKind.IMAGE,
            BlockKind.ATTRIBUTE,
            BlockKind.INCLUDE,
        )

        /** Kinds shared with Markdown: their sanitized HTML content marks them as AsciiDoc. */
        private val ASCIIDOC_HTML_KINDS = setOf(
            BlockKind.HEADING,
            BlockKind.PARAGRAPH,
            BlockKind.LIST_ITEM,
            BlockKind.TABLE_ROW,
            BlockKind.ALERT,
            BlockKind.FOOTNOTE,
            BlockKind.THEMATIC_BREAK,
        )

        /** Kinds where a markup-only change is reported as "formatting changed". */
        private val FORMATTING_KINDS = setOf(
            BlockKind.HEADING,
            BlockKind.PARAGRAPH,
            BlockKind.LIST_ITEM,
            BlockKind.DESCRIPTION_TERM,
            BlockKind.TABLE_ROW,
            BlockKind.ALERT,
            BlockKind.BLOCK_TITLE,
            BlockKind.FOOTNOTE,
        )

        private val asciiDocCheckbox = Regex("""(?m)^\s*(?:[*.-]|\d+\.)\s+\[([xX* ])]""")

        /** Kinds rendered from their source, where comments are text. */
        private val RAW_KINDS = setOf(BlockKind.CODE_LINE, BlockKind.CODE_FENCE, BlockKind.FRONT_MATTER, BlockKind.MERMAID, BlockKind.MATH)

        /** Kinds drawn by the page script, whose changes show both drawings and a toggle to the source diff. */
        private val DRAWN_KINDS = setOf(BlockKind.MERMAID, BlockKind.MATH)

        /**
         * The parser's extensions minus footnotes: their renderer collects definitions at the end of the
         * document, while here each definition is a block of its own ([FootnoteReferenceRenderer]).
         */
        private val renderExtensions = MarkdownBlockSource.extensions.filterNot { it is FootnotesExtension }

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
