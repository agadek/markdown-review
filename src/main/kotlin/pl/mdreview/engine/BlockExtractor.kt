package pl.mdreview.engine

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.alerts.Alert
import org.commonmark.ext.gfm.alerts.AlertTitle
import org.commonmark.ext.gfm.alerts.AlertsExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.SourceSpan
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** Parses Markdown and flattens it into the list of blocks the diff engine compares. */
object BlockExtractor {

    /** Key of the [BlockKind.CODE_FENCE] block that opens YAML front matter. */
    const val FRONT_MATTER_FENCE_KEY = "CODE_FENCE:front-matter"

    /** Info string of a fence that is drawn as a Mermaid diagram. */
    const val MERMAID_LANGUAGE = "mermaid"

    val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
        FootnotesExtension.create(),
        AlertsExtension.create(),
        EmojiExtension.create(),
    )

    private val parser: Parser = Parser.builder()
        .extensions(extensions)
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    /** @param mermaidDiagrams keep ` ```mermaid ` fences whole, see [BlockKind.MERMAID]. */
    fun extract(markdown: String, mermaidDiagrams: Boolean = true): List<Block> {
        val text = normalizeLineEndings(markdown)
        val frontMatter = findFrontMatter(text)
        val blocks = mutableListOf<Block>()
        var parseInput = text
        if (frontMatter != null) {
            blocks += frontMatter.blocks
            // Blank out the front matter so offsets in the rest of the file stay valid.
            parseInput = text.substring(0, frontMatter.endOffset).replace(Regex("[^\n]"), " ") +
                text.substring(frontMatter.endOffset)
        }
        val document = parser.parse(parseInput) as Document
        Walker(parseInput, blocks, mermaidDiagrams).visitChildren(document, BlockContext())
        return blocks
    }

    fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private class FrontMatter(val blocks: List<Block>, val endOffset: Int)

    private fun findFrontMatter(text: String): FrontMatter? {
        if (!text.startsWith("---\n")) return null
        val lines = text.split('\n')
        val close = (1 until lines.size).firstOrNull { lines[it] == "---" || lines[it] == "..." } ?: return null
        val blocks = mutableListOf<Block>()
        blocks += Block(
            kind = BlockKind.CODE_FENCE,
            key = FRONT_MATTER_FENCE_KEY,
            plainText = "",
            rawText = lines[0],
            source = SourceRange(0, 1, 0, lines[0].length),
            language = "yaml",
        )
        var offset = lines[0].length + 1
        for (i in 1 until close) {
            val line = lines[i]
            blocks += Block(
                kind = BlockKind.FRONT_MATTER,
                key = "fm:" + collapse(line),
                plainText = line,
                rawText = line,
                source = SourceRange(i, i + 1, offset, offset + line.length),
                language = "yaml",
            )
            offset += line.length + 1
        }
        val end = offset + lines[close].length + if (close + 1 < lines.size) 1 else 0
        return FrontMatter(blocks, end)
    }

    private class Walker(val input: String, var out: MutableList<Block>, val mermaidDiagrams: Boolean) {

        fun visitChildren(parent: Node, ctx: BlockContext) =
            visitNodes(generateSequence(parent.firstChild) { it.next }.toList(), ctx)

        /**
         * Visits sibling blocks. An HTML block that opens a `<details>` closed by a later sibling becomes a
         * [BlockKind.DETAILS] block, and the blocks up to the closing one are its content.
         */
        private fun visitNodes(nodes: List<Node>, ctx: BlockContext) {
            var k = 0
            while (k < nodes.size) {
                val node = nodes[k]
                val close = if (node is HtmlBlock) detailsClose(nodes, k) else -1
                if (close < 0) {
                    visit(node, ctx)
                    k++
                    continue
                }
                out += htmlBlock(BlockKind.DETAILS, node as HtmlBlock, ctx)
                val inner = ctx.copy(details = ctx.details + 1)
                visitNodes(nodes.subList(k + 1, close), inner)
                // Whatever the closing block holds besides `</details>` belongs to the content.
                val closing = nodes[close] as HtmlBlock
                val rest = closing.literal.replace(DETAILS_CLOSE, "")
                if (rest.isNotBlank()) out += html(closing, inner, rest)
                k = close + 1
            }
        }

        /** Index of the sibling that closes the `<details>` opened by `nodes[start]`, or -1. */
        private fun detailsClose(nodes: List<Node>, start: Int): Int {
            var balance = HtmlFragment.detailsBalance((nodes[start] as HtmlBlock).literal)
            if (balance <= 0) return -1
            for (k in start + 1 until nodes.size) {
                val node = nodes[k] as? HtmlBlock ?: continue
                balance += HtmlFragment.detailsBalance(node.literal)
                if (balance <= 0) return k
            }
            return -1
        }

        /** An HTML block, or an [BlockKind.HTML_COMMENT] when it is exactly one comment. */
        private fun html(node: HtmlBlock, ctx: BlockContext, literal: String = node.literal): Block {
            val comment = HtmlFragment.singleComment(literal)
            if (comment != null) {
                return Block(
                    kind = BlockKind.HTML_COMMENT,
                    key = keyPrefix(BlockKind.HTML_COMMENT, ctx) + collapse(comment),
                    plainText = comment,
                    rawText = raw(node.sourceSpans),
                    source = range(node.sourceSpans),
                    context = ctx,
                    node = node,
                )
            }
            return htmlBlock(BlockKind.HTML, node, ctx, literal)
        }

        /** HTML is keyed, compared and rendered by its literal, without the quote markers around it. */
        private fun htmlBlock(kind: BlockKind, node: HtmlBlock, ctx: BlockContext, literal: String = node.literal): Block =
            Block(
                kind = kind,
                key = keyPrefix(kind, ctx) + collapse(literal),
                plainText = HtmlFragment.visibleText(literal),
                rawText = literal.trimEnd(),
                source = range(node.sourceSpans),
                context = ctx,
                node = node,
            )

        private fun visit(node: Node, ctx: BlockContext) {
            when (node) {
                is Heading -> out += heading(node, ctx)
                is Paragraph -> out += simple(BlockKind.PARAGRAPH, node, ctx)
                is ThematicBreak -> out += thematicBreak(node, ctx)
                is HtmlBlock -> out += html(node, ctx)
                is BlockQuote -> visitChildren(node, ctx.copy(quoteDepth = ctx.quoteDepth + 1))
                is ListBlock -> visitList(node, ctx)
                is FencedCodeBlock -> visitFencedCode(node, ctx)
                is IndentedCodeBlock -> visitCodeLines(node, node.literal, node.sourceSpans, null, ctx)
                is TableBlock -> visitTable(node, ctx)
                is FootnoteDefinition -> out += footnote(node, ctx)
                is Alert -> visitAlert(node, ctx)
                else -> Unit // link reference definitions and other non-visual blocks
            }
        }

        private fun simple(kind: BlockKind, node: Node, ctx: BlockContext, plain: String? = null): Block {
            val raw = raw(node.sourceSpans)
            return Block(
                kind = kind,
                key = keyPrefix(kind, ctx) + collapse(keyText(node.sourceSpans, listOf(node))) + hardBreaks(listOf(node)) +
                    linkTargets(listOf(node)),
                plainText = plain ?: plainText(node),
                rawText = raw,
                source = range(node.sourceSpans),
                context = ctx,
                node = node,
            )
        }

        /** `---`, `***` and `___` draw the same line, so a thematic break is keyed by its kind alone. */
        private fun thematicBreak(node: ThematicBreak, ctx: BlockContext): Block {
            val block = simple(BlockKind.THEMATIC_BREAK, node, ctx)
            return Block(block.kind, keyPrefix(BlockKind.THEMATIC_BREAK, ctx) + "---", "", block.rawText, block.source, ctx, node)
        }

        /**
         * Headings compare by level and inline content, so ATX vs Setext syntax and extra spaces after
         * `#` are not changes.
         */
        private fun heading(node: Heading, ctx: BlockContext): Block {
            val inline = generateSequence(node.firstChild) { it.next }.flatMap { it.sourceSpans }.toList()
            val content = if (inline.isEmpty()) "" else {
                val first = inline.first()
                val last = inline.last()
                input.substring(first.inputIndex, last.inputIndex + last.length)
            }
            return Block(
                kind = BlockKind.HEADING,
                key = keyPrefix(BlockKind.HEADING, ctx) + "h" + node.level + ":" + collapse(content) + linkTargets(listOf(node)),
                plainText = plainText(node),
                rawText = raw(node.sourceSpans),
                source = range(node.sourceSpans),
                context = ctx,
                node = node,
                level = node.level,
            )
        }

        /** A footnote definition is one block; its paragraphs are rendered one after another, like a list item's. */
        private fun footnote(node: FootnoteDefinition, ctx: BlockContext): Block {
            val content = generateSequence(node.firstChild) { it.next }.toList()
            val raw = raw(node.sourceSpans)
            return Block(
                kind = BlockKind.FOOTNOTE,
                key = keyPrefix(BlockKind.FOOTNOTE, ctx) + collapse(keyText(node.sourceSpans, content)) + hardBreaks(content),
                plainText = content.joinToString(" ") { plainText(it) },
                rawText = raw,
                source = range(node.sourceSpans),
                context = ctx,
                node = node,
                renderNodes = content,
            )
        }

        /**
         * An alert is a quote with a title: the marker line becomes an [BlockKind.ALERT] block keyed by the
         * type, and the content is flattened like a quote's, so changing only the type modifies the title.
         */
        private fun visitAlert(node: Alert, ctx: BlockContext) {
            val inner = ctx.copy(quoteDepth = ctx.quoteDepth + 1, alert = ctx.alert ?: node.type)
            val marker = node.sourceSpans.firstOrNull()
            out += Block(
                kind = BlockKind.ALERT,
                key = keyPrefix(BlockKind.ALERT, inner) + node.type,
                plainText = alertTitle(node.type),
                rawText = marker?.let(::slice).orEmpty(),
                source = range(listOfNotNull(marker)),
                context = inner,
                node = node,
            )
            var child = node.firstChild
            while (child != null) {
                if (child !is AlertTitle) visit(child, inner)
                child = child.next
            }
        }

        private fun visitList(list: ListBlock, ctx: BlockContext) {
            val ordered = list is OrderedList
            var number = (list as? OrderedList)?.markerStartNumber ?: 1
            var item = list.firstChild
            while (item != null) {
                if (item is ListItem) {
                    val itemCtx = ctx.copy(
                        listDepth = ctx.listDepth + 1,
                        ordered = ordered,
                        number = if (ordered) number else null,
                    )
                    visitListItem(item, itemCtx)
                    number++
                }
                item = item.next
            }
        }

        private fun visitListItem(item: ListItem, ctx: BlockContext) {
            val content = generateSequence(item.firstChild) { it.next }.filter { it !is ListBlock }.toList()
            val nested = generateSequence(item.firstChild) { it.next }.filterIsInstance<ListBlock>().toList()
            val spans = content.flatMap { it.sourceSpans }
            val raw = raw(spans)
            val task = findTaskMarker(content)
            val taskKey = when {
                task == null -> ""
                task.isChecked -> "[x] "
                else -> "[ ] "
            }
            val start = item.sourceSpans.firstOrNull()
            val rangeSpans = listOfNotNull(start) + spans
            out += Block(
                kind = BlockKind.LIST_ITEM,
                key = keyPrefix(BlockKind.LIST_ITEM, ctx) + taskKey + collapse(keyText(spans, content)) + hardBreaks(content) +
                    linkTargets(content),
                plainText = content.joinToString(" ") { plainText(it) },
                rawText = raw,
                source = range(rangeSpans),
                context = ctx,
                node = item,
                renderNodes = content,
            )
            nested.forEach { visitList(it, ctx) }
        }

        private fun findTaskMarker(content: List<Node>): TaskListItemMarker? {
            for (node in content) {
                var child = node.firstChild
                while (child != null) {
                    if (child is TaskListItemMarker) return child
                    child = child.next
                }
                if (node is TaskListItemMarker) return node
            }
            return null
        }

        private fun visitFencedCode(node: FencedCodeBlock, ctx: BlockContext) {
            val spans = node.sourceSpans
            val info = node.info.orEmpty().trim()
            val language = info.substringBefore(' ').ifEmpty { null }
            val opening = spans.firstOrNull()
            val diagram = mermaidDiagrams && language.equals(MERMAID_LANGUAGE, ignoreCase = true) && opening != null
            val parent = out
            if (diagram) out = mutableListOf()
            if (opening != null) {
                out += Block(
                    kind = BlockKind.CODE_FENCE,
                    key = keyPrefix(BlockKind.CODE_FENCE, ctx) + info,
                    plainText = info,
                    rawText = slice(opening),
                    source = range(listOf(opening)),
                    context = ctx,
                    language = language,
                )
            }
            visitCodeLines(node, node.literal, spans.drop(1), language, ctx)
            if (diagram) {
                val lines = out
                out = parent
                val source = node.literal.removeSuffix("\n")
                out += Block(
                    kind = BlockKind.MERMAID,
                    // Like code, a diagram is compared exactly except for trailing whitespace on its lines.
                    key = keyPrefix(BlockKind.MERMAID, ctx) + source.lines().joinToString("\n") { it.trimEnd() },
                    plainText = source,
                    rawText = raw(spans),
                    source = range(spans),
                    context = ctx,
                    language = language,
                    lines = lines,
                )
            }
        }

        private fun visitCodeLines(node: Node, literal: String, spans: List<SourceSpan>, language: String?, ctx: BlockContext) {
            val lines = literal.removeSuffix("\n").let { if (it.isEmpty() && literal.isEmpty()) emptyList() else it.split('\n') }
            lines.forEachIndexed { i, line ->
                val span = spans.getOrNull(i)
                val source = if (span != null) range(listOf(span)) else range(node.sourceSpans)
                out += Block(
                    kind = BlockKind.CODE_LINE,
                    // Code is compared exactly; only trailing whitespace is ignored.
                    key = keyPrefix(BlockKind.CODE_LINE, ctx) + line.trimEnd(),
                    plainText = line,
                    rawText = line,
                    source = source,
                    context = ctx,
                    language = language,
                )
            }
        }

        private fun visitTable(table: TableBlock, ctx: BlockContext) {
            var section = table.firstChild
            while (section != null) {
                if (section is TableHead || section is TableBody) {
                    var row = section.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            val header = section is TableHead
                            val block = simple(BlockKind.TABLE_ROW, row, ctx)
                            out += Block(
                                kind = block.kind,
                                key = (if (header) "th:" else "td:") + block.key,
                                plainText = block.plainText,
                                rawText = block.rawText,
                                source = block.source,
                                context = ctx,
                                node = row,
                            )
                        }
                        row = row.next
                    }
                }
                section = section.next
            }
        }

        private fun slice(span: SourceSpan): String =
            input.substring(span.inputIndex, (span.inputIndex + span.length).coerceAtMost(input.length))

        private fun raw(spans: List<SourceSpan>): String = spans.joinToString("\n") { slice(it) }

        /**
         * Source of [spans] for the block key, without the backslash of `\`-style hard line breaks in [nodes], so
         * both hard break syntaxes give the same key; [hardBreaks] adds where the breaks are.
         */
        private fun keyText(spans: List<SourceSpan>, nodes: List<Node>): String {
            val backslashes = mutableSetOf<Int>()
            nodes.forEach { n ->
                n.accept(object : AbstractVisitor() {
                    override fun visit(hardLineBreak: HardLineBreak) {
                        val span = hardLineBreak.sourceSpans.firstOrNull() ?: return
                        if (input.getOrNull(span.inputIndex) == '\\') backslashes += span.inputIndex
                    }
                })
            }
            if (backslashes.isEmpty()) return raw(spans)
            return spans.joinToString("\n") { span ->
                val start = span.inputIndex
                val text = slice(span)
                buildString { text.forEachIndexed { i, c -> if (start + i !in backslashes) append(c) } }
            }
        }

        private fun range(spans: List<SourceSpan>): SourceRange {
            val first = spans.firstOrNull() ?: return SourceRange(0, 0, 0, 0)
            val last = spans.last()
            return SourceRange(first.lineIndex, last.lineIndex + 1, first.inputIndex, last.inputIndex + last.length)
        }
    }

    /** Title GitHub shows for an alert type, for example "Note" for `NOTE`. */
    fun alertTitle(type: String): String =
        AlertsExtension.STANDARD_TYPES[type] ?: type.lowercase().replaceFirstChar { it.uppercase() }

    private fun keyPrefix(kind: BlockKind, ctx: BlockContext): String = buildString {
        append(kind.name).append(':')
        if (ctx.quoteDepth > 0) append("q").append(ctx.quoteDepth).append(':')
        if (ctx.details > 0) append("d").append(ctx.details).append(':')
        if (kind == BlockKind.LIST_ITEM) append(if (ctx.ordered) "ol" else "ul").append(ctx.listDepth).append(':')
    }

    /**
     * Destinations of the links and images in [nodes], as a key suffix. commonmark resolves reference
     * links through their `[label]: url` definitions, so a changed definition changes the key of every
     * block that uses it, even though the block's own source is unchanged.
     */
    fun linkTargets(nodes: List<Node>): String {
        val targets = mutableListOf<String>()
        val visitor = object : AbstractVisitor() {
            override fun visit(link: Link) {
                targets += link.destination
                visitChildren(link)
            }

            override fun visit(image: Image) {
                targets += image.destination
                visitChildren(image)
            }
        }
        nodes.forEach { it.accept(visitor) }
        return if (targets.isEmpty()) "" else " ->" + targets.joinToString(" ", prefix = " ")
    }

    /**
     * Where the hard line breaks of [nodes] are, as a key suffix: a hard break renders as a new line, so adding or
     * removing one is a change even though whitespace is collapsed in the key.
     */
    fun hardBreaks(nodes: List<Node>): String {
        val positions = nodes.flatMapIndexed { i, node ->
            val plain = plainTextWithOffsets(node)
            plain.offsets.filterKeys { it is HardLineBreak }.values.sorted().map { "$i.$it" }
        }
        return if (positions.isEmpty()) "" else " $HARD_BREAK_MARK" + positions.joinToString(",")
    }

    private const val HARD_BREAK_MARK = '\u21b5'

    /** The [hardBreaks] part of a block key. */
    val HARD_BREAKS_KEY = Regex(" $HARD_BREAK_MARK[0-9.,]+")

    /** Visible text of a node plus the offset of every [Text], [Code] and [HardLineBreak] node within it. */
    class PlainText(val text: String, val offsets: Map<Node, Int>)

    /**
     * Visible text of a node, as the renderer emits it: [Text] and [Code] literals, soft line breaks as
     * spaces, hard line breaks as `\n`, a space between table cells, footnote references as `[^label]`. Image alt text is
     * skipped because the HTML renderer writes it into an attribute.
     */
    fun plainTextWithOffsets(node: Node): PlainText {
        val sb = StringBuilder()
        val offsets = java.util.IdentityHashMap<Node, Int>()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                offsets[text] = sb.length
                sb.append(text.literal)
            }

            override fun visit(code: Code) {
                offsets[code] = sb.length
                sb.append(code.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) { sb.append(' ') }
            override fun visit(hardLineBreak: HardLineBreak) {
                offsets[hardLineBreak] = sb.length
                sb.append('\n')
            }
            override fun visit(image: Image) = Unit

            override fun visit(customNode: CustomNode) {
                if (customNode is FootnoteReference) {
                    offsets[customNode] = sb.length
                    sb.append("[^").append(customNode.label).append(']')
                    return
                }
                if (customNode is TableCell && customNode.previous != null) sb.append(' ')
                visitChildren(customNode)
            }
        })
        return PlainText(sb.toString(), offsets)
    }

    fun plainText(node: Node): String = plainTextWithOffsets(node).text

    private val whitespace = Regex("\\s+")

    fun collapse(text: String): String = text.replace(whitespace, " ").trim()

    private val DETAILS_CLOSE = Regex("</details\\s*>", RegexOption.IGNORE_CASE)
}
