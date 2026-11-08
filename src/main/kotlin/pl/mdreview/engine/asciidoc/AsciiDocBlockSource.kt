package pl.mdreview.engine.asciidoc

import org.asciidoctor.Attributes
import org.asciidoctor.Options
import org.asciidoctor.SafeMode
import org.asciidoctor.ast.Block as AstBlock
import org.asciidoctor.ast.Cell
import org.asciidoctor.ast.DescriptionList
import org.asciidoctor.ast.Document
import org.asciidoctor.ast.List as AstList
import org.asciidoctor.ast.ListItem
import org.asciidoctor.ast.Row
import org.asciidoctor.ast.Section
import org.asciidoctor.ast.StructuralNode
import org.asciidoctor.ast.Table
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.BlockContext
import pl.mdreview.engine.BlockGroup
import pl.mdreview.engine.BlockGroupKind
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.BlockSource
import pl.mdreview.engine.Container
import pl.mdreview.engine.DiffOptions
import pl.mdreview.engine.HtmlFragment
import pl.mdreview.engine.SourceRange

/**
 * The AsciiDoc front end: parses a document with AsciidoctorJ and flattens its tree into [Block]s per ADR 0005.
 *
 * The parser runs in `SECURE` safe mode with a source map, no base directory and no output file, so it reads no
 * files and no network. Anything the parser cannot process (including a missing AsciidoctorJ at run time) falls back
 * to raw blank-line-separated paragraphs, so a broken document still shows something.
 *
 * Platform-free: only AsciidoctorJ and the engine model, no IntelliJ APIs (NFR-15).
 */
class AsciiDocBlockSource(private val provider: AsciidoctorProvider) : BlockSource {

    override fun extract(text: String, options: DiffOptions): List<Block> {
        val normalized = normalizeLineEndings(text)
        return try {
            val document = provider.asciidoctor().load(normalized, options())
            Extractor(normalized).extract(document)
        } catch (_: Exception) {
            fallback(normalized)
        } catch (_: LinkageError) {
            fallback(normalized)
        }
    }

    private fun options(): Options =
        Options.builder()
            .safe(SafeMode.SECURE)
            .sourcemap(true)
            .option("parse_header_only", false)
            .attributes(Attributes.builder().attribute("skip-front-matter", true).build())
            .build()

    /** Raw paragraphs, one per blank-line-separated run, with their source lines. */
    private fun fallback(text: String): List<Block> {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size + 1)
        var offset = 0
        for (i in lines.indices) {
            offsets[i] = offset
            offset += lines[i].length + 1
        }
        offsets[lines.size] = offset
        val blocks = mutableListOf<Block>()
        var start = -1
        fun emit(end: Int) {
            if (start < 0) return
            val chunk = lines.subList(start, end).joinToString("\n")
            blocks += Block(
                kind = BlockKind.PARAGRAPH,
                key = "PARAGRAPH:-:" + normalizeSource(chunk) + "\u0000" + chunk.trim(),
                plainText = chunk,
                rawText = chunk,
                source = SourceRange(start, end, offsets[start], minOf(offsets[end], text.length)),
            )
            start = -1
        }
        for (i in 0..lines.size) {
            val blank = i == lines.size || lines[i].isBlank()
            if (!blank && start < 0) start = i
            if (blank) emit(i)
        }
        return blocks
    }

    private class Extractor(private val text: String) {

        private val lines = AsciiDocLines(text)
        private val drafts = mutableListOf<Draft>()
        private val groups = mutableListOf<BlockGroup>()
        private val itemGroups = mutableListOf<ItemGroupDraft>()
        private var lastStart = 0

        fun extract(document: Document): List<Block> {
            lines.rows.forEach(::addScannerRow)
            addHeaderGroup()
            visitDocument(document)
            return finish()
        }

        // ---- edit groups ------------------------------------------------------------------------------------

        /** The source range of a delimited group opening at [openLine], or null when it is not delimited. */
        private fun delimitedGroup(kind: BlockGroupKind, openLine: Int): BlockGroup? {
            val delimiter = lines.openingDelimiter(openLine) ?: return null
            val close = (delimiter.closeLine ?: (lines.lines.size - 1)).coerceAtMost(lines.lines.size - 1)
            val prefix = lines.editPrefixBefore(openLine)
            return BlockGroup(
                kind = kind,
                startLine = (prefix + openLine).minOrNull() ?: openLine,
                endLine = close + 1,
                openLine = openLine,
                closeLine = close,
                prefixLines = prefix,
            )
        }

        private fun addGroup(kind: BlockGroupKind, openLine: Int) {
            delimitedGroup(kind, openLine)?.let(groups::add)
        }

        /** The document header is one region when added or removed: title through the last header line. */
        private fun addHeaderGroup() {
            val title = lines.titleLine ?: return
            val end = lines.headerEnd.coerceAtLeast(title + 1)
            groups += BlockGroup(BlockGroupKind.HEADER, startLine = title, endLine = end, openLine = title)
        }

        // ---- scanner rows -----------------------------------------------------------------------------------

        private fun addScannerRow(row: AsciiDocLines.Row) {
            val content = when (row.kind) {
                BlockKind.HEADING -> textContent(row.plainText)
                BlockKind.ATTRIBUTE -> textContent(row.plainText)
                BlockKind.BLOCK_TITLE -> textContent(row.plainText)
                BlockKind.HTML_COMMENT -> textContent(row.plainText)
                BlockKind.INCLUDE -> textContent(row.plainText)
                else -> BlockContent.Raw
            }
            drafts += Draft(
                kind = row.kind,
                start = row.startLine,
                fixedEnd = row.endLine,
                sourceText = row.rawText,
                plainText = row.plainText,
                context = BlockContext(containers = row.containers),
                content = content,
                level = row.level,
                target = row.target,
                scanner = true,
            )
        }

        // ---- tree walk --------------------------------------------------------------------------------------

        private fun visitDocument(document: Document) {
            document.blocks.forEach { visit(it, BlockContext()) }
        }

        private fun visit(node: StructuralNode, ctx: BlockContext, listDepth: Int = 0) {
            when (node) {
                is Section -> {
                    heading(node, node.title.orEmpty(), node.level + 1, ctx)
                    node.blocks.forEach { visit(it, ctx, listDepth) }
                }
                is AstList -> visitList(node, ctx, if (listDepth <= 0) 1 else listDepth)
                is DescriptionList -> visitDescriptionList(node, ctx)
                is Table -> visitTable(node, ctx)
                else -> visitByContext(node, ctx, listDepth)
            }
        }

        private fun visitByContext(node: StructuralNode, ctx: BlockContext, listDepth: Int) {
            when (node.context) {
                "document", "preamble" -> node.blocks.forEach { visit(it, ctx, listDepth) }
                "paragraph" -> paragraph(node, ctx)
                "literal" -> if (lines.openingDelimiter(startOf(node)) != null) {
                    code(node as AstBlock, ctx)
                } else {
                    literalParagraph(node as AstBlock, ctx)
                }
                "listing" -> code(node as AstBlock, ctx)
                "admonition" -> admonition(node, ctx)
                "example", "sidebar", "open" -> container(node, ctx, node.context)
                "quote" -> container(node, ctx, if (node.style == "verse") "verse" else "quote")
                "verse" -> container(node, ctx, "verse")
                "image" -> image(node, ctx)
                "thematic_break" -> thematicBreak(node, ctx, page = false)
                "page_break" -> thematicBreak(node, ctx, page = true)
                "pass" -> pass(node as AstBlock, ctx)
                "floating_title" -> heading(node, node.title.orEmpty(), node.level + 1, ctx)
                "toc" -> Unit
                else -> {
                    // Unknown structural nodes: descend so no content disappears; a leaf without a known
                    // context has no representation in the block model.
                    if (node.blocks.isNotEmpty()) node.blocks.forEach { visit(it, ctx, listDepth) }
                }
            }
        }

        private fun heading(node: StructuralNode, title: String, level: Int, ctx: BlockContext) {
            if (title.isBlank()) return
            val start = startOf(node)
            val parts = HtmlFragment.parse(title, allowRemoteImages = true)
            drafts += Draft(
                kind = BlockKind.HEADING,
                start = start,
                plainText = HtmlFragment.visibleText(parts),
                context = ctx,
                content = BlockContent.Html(parts),
                level = level.coerceIn(1, 6),
            )
        }

        private fun paragraph(node: StructuralNode, ctx: BlockContext) {
            val start = startOf(node)
            // SECURE mode turns include:: into a paragraph with the include link; the scanner owns that line.
            if (start in lines.includeLines) return
            val html = (node.content as? String) ?: (node as AstBlock).lines.orEmpty().joinToString("\n")
            val parts = HtmlFragment.parse(html, allowRemoteImages = true)
            drafts += Draft(
                kind = BlockKind.PARAGRAPH,
                start = start,
                plainText = HtmlFragment.visibleText(parts),
                context = ctx,
                content = BlockContent.Html(parts),
            )
        }

        private fun literalParagraph(node: AstBlock, ctx: BlockContext) {
            val start = startOf(node)
            node.lines.orEmpty().forEachIndexed { i, line ->
                drafts += codeLine(start + i, line, ctx, null)
            }
        }

        /**
         * One `ALERT` block per admonition paragraph. The label comes from the style when the renderer draws it;
         * [Block.plainText] is the body text, so the word diff runs on the paragraph content and a type change is a
         * change even when the body is equal.
         */
        private fun admonition(node: StructuralNode, ctx: BlockContext) {
            val type = (node.style ?: "NOTE").uppercase()
            val start = startOf(node)
            if (node.context == "admonition" && node.blocks.isNotEmpty()) {
                addGroup(BlockGroupKind.CONTAINER, start)
                val inner = ctx.copy(containers = ctx.containers + Container("admonition", type))
                node.blocks.forEach { visit(it, inner) }
                return
            }
            val html = (node.content as? String) ?: (node as AstBlock).lines.orEmpty().joinToString("\n")
            val parts = HtmlFragment.parse(html, allowRemoteImages = true)
            val inner = ctx.copy(containers = ctx.containers + Container("admonition", type))
            drafts += Draft(
                kind = BlockKind.ALERT,
                start = start,
                sourceText = "$type:",
                plainText = HtmlFragment.visibleText(parts),
                context = inner,
                content = BlockContent.Html(parts),
                extraKey = "alert=$type",
            )
        }

        private fun container(node: StructuralNode, ctx: BlockContext, kind: String) {
            val start = startOf(node)
            addGroup(BlockGroupKind.CONTAINER, start)
            val type = when (kind) {
                "admonition" -> (node.style ?: "NOTE").uppercase()
                else -> null
            }
            val title = when (kind) {
                "quote", "verse" -> (node.getAttribute("attribution") as? String)?.takeIf { it.isNotBlank() }
                    ?: node.title?.takeIf { it.isNotBlank() }
                else -> node.title?.takeIf { it.isNotBlank() }
            }
            val inner = ctx.copy(containers = ctx.containers + Container(kind, type, title))
            node.blocks.forEach { visit(it, inner) }
        }

        private fun visitList(list: AstList, ctx: BlockContext, depth: Int) {
            val ordered = list.context == "olist" || list.context == "colist"
            val callout = list.context == "colist"
            var number = if (ordered) startNumber(list) else 1
            for (item in list.items) {
                if (item !is ListItem) continue
                val start = startOf(item)
                val containers = ctx.containers + if (callout) listOf(Container("callout")) else emptyList()
                val itemCtx = ctx.copy(
                    listDepth = depth,
                    ordered = ordered,
                    number = if (ordered) number else null,
                    containers = containers,
                )
                val task = taskKey(item)
                val html = item.text.orEmpty()
                val parts = HtmlFragment.parse(html, allowRemoteImages = true)
                val firstDraft = drafts.size
                val itemDraft = Draft(
                    kind = BlockKind.LIST_ITEM,
                    start = start,
                    plainText = HtmlFragment.visibleText(parts),
                    context = itemCtx,
                    content = BlockContent.Html(parts),
                    extraKey = task,
                )
                drafts += itemDraft
                val childCtx = itemCtx.copy(listDepth = depth + 1, ordered = false, number = null)
                for (child in item.blocks) {
                    if (child is AstList) visitList(child, itemCtx, depth + 1) else visit(child, childCtx, depth + 1)
                }
                if (drafts.size > firstDraft + 1) {
                    itemGroups += ItemGroupDraft(itemDraft, drafts.subList(firstDraft, drafts.size).toList())
                }
                number++
            }
        }

        private fun startNumber(list: AstList): Int {
            (list.getAttribute("start", null) as? Number)?.let { return it.toInt() }
            (list.getAttribute("start", null) as? String)?.toIntOrNull()?.let { return it }
            return 1
        }

        private fun taskKey(item: ListItem): String = when {
            !item.hasAttribute("checkbox") -> ""
            item.hasAttribute("checked") -> "task=checked"
            else -> "task=unchecked"
        }

        private fun visitDescriptionList(list: DescriptionList, ctx: BlockContext) {
            val descriptionCtx = ctx.copy(containers = ctx.containers + Container("description"))
            for (entry in list.items) {
                val termLine = entry.terms.firstOrNull()?.let { startOf(it) } ?: lastStart
                val segments = splitDescriptionEntry(lines.sourceLine(termLine))
                for ((index, term) in entry.terms.withIndex()) {
                    val start = startOf(term)
                    val parts = HtmlFragment.parse(term.text.orEmpty(), allowRemoteImages = true)
                    drafts += Draft(
                        kind = BlockKind.DESCRIPTION_TERM,
                        start = start,
                        sourceText = segments.getOrNull(index) ?: segments.firstOrNull(),
                        plainText = HtmlFragment.visibleText(parts),
                        context = descriptionCtx.copy(listDepth = 1, ordered = false, number = null),
                        content = BlockContent.Html(parts),
                        extraKey = "description=term",
                    )
                }
                val description = entry.description ?: continue
                val start = startOf(description)
                val parts = HtmlFragment.parse(description.text.orEmpty(), allowRemoteImages = true)
                drafts += Draft(
                    kind = BlockKind.LIST_ITEM,
                    start = start,
                    sourceText = segments.getOrNull(entry.terms.size) ?: segments.lastOrNull(),
                    plainText = HtmlFragment.visibleText(parts),
                    context = descriptionCtx.copy(listDepth = 1, ordered = false, number = null),
                    content = BlockContent.Html(parts),
                    extraKey = "description=item",
                )
                for (child in description.blocks) {
                    visit(child, descriptionCtx.copy(listDepth = 2, ordered = false, number = null), 2)
                }
            }
        }

        /** The source of a description entry split at its `::` separators: terms first, description last. */
        private fun splitDescriptionEntry(line: String): List<String> {
            if (!line.contains("::")) return listOf(line)
            return line.split("::").map { it.trim() }
        }

        private fun visitTable(table: Table, ctx: BlockContext) {
            addGroup(BlockGroupKind.TABLE, startOf(table))
            visitRows(table.header, header = true, ctx = ctx)
            visitRows(table.body, header = false, ctx = ctx)
            visitRows(table.footer, header = false, ctx = ctx)
        }

        private fun visitRows(rows: List<Row>, header: Boolean, ctx: BlockContext) {
            for (row in rows) {
                val cellLines = row.cells.mapNotNull { cell -> cell.sourceLocation?.lineNumber }
                    .filter { it > 0 }
                val rowStart = if (cellLines.isEmpty()) lastStart else cellLines.min() - 1
                val start = startAt(maxOf(rowStart, lastStart))
                val parts = mutableListOf<HtmlFragment.Part>()
                val texts = mutableListOf<String>()
                for (cell in row.cells) {
                    val tag = if (header) "th" else "td"
                    parts += HtmlFragment.Markup("<$tag>", tag, false)
                    val cellParts = cellParts(cell)
                    parts += cellParts
                    parts += HtmlFragment.Markup("</$tag>", tag, true)
                    texts += HtmlFragment.visibleText(cellParts).replace(WHITESPACE, " ").trim()
                }
                drafts += Draft(
                    kind = BlockKind.TABLE_ROW,
                    start = start,
                    plainText = texts.joinToString(" | "),
                    context = ctx,
                    content = BlockContent.Html(parts),
                    tableHeader = header,
                    minimumEnd = if (cellLines.isEmpty()) start + 1 else cellLines.max(),
                )
            }
        }

        private fun cellParts(cell: Cell): List<HtmlFragment.Part> {
            val html = if (cell.style == "asciidoc") {
                (cell.content as? String) ?: cell.text.orEmpty()
            } else {
                cell.text.orEmpty()
            }
            return HtmlFragment.parse(html, allowRemoteImages = true)
        }

        private fun code(node: AstBlock, ctx: BlockContext) {
            val start = startOf(node)
            addGroup(BlockGroupKind.CODE, start)
            val style = node.style ?: node.context
            val language = (node.attributes["language"] as? String)?.takeIf { it.isNotBlank() }
            val prefix = lines.prefixesBefore(start).lastOrNull { isSourceAttribute(lines.sourceLine(it)) }
            val fenceStart = prefix ?: start
            val info = listOfNotNull(
                style.takeIf { it.isNotBlank() },
                language,
            ).joinToString(",")
            drafts += Draft(
                kind = BlockKind.CODE_FENCE,
                start = fenceStart,
                fixedEnd = fenceStart + 1,
                sourceText = lines.sourceLine(fenceStart).trimEnd(),
                plainText = info,
                context = ctx,
                language = language,
                extraKey = "style=$style;language=${language ?: ""}",
            )
            node.lines.orEmpty().forEachIndexed { i, line ->
                drafts += codeLine(start + 1 + i, line, ctx, language)
            }
        }

        private fun codeLine(lineNumber: Int, line: String, ctx: BlockContext, language: String?): Draft =
            Draft(
                kind = BlockKind.CODE_LINE,
                start = lineNumber,
                fixedEnd = lineNumber + 1,
                sourceText = line,
                plainText = line,
                context = ctx,
                language = language,
            )

        private fun image(node: StructuralNode, ctx: BlockContext) {
            val start = startOf(node)
            val attributes = node.attributes
            val target = attributes["target"]?.toString().orEmpty()
            val alt = attributes["alt"]?.toString().orEmpty()
            val title = node.title?.takeIf { it.isNotBlank() }
            val img = StringBuilder("<img src=\"").append(escapeAttribute(target)).append("\" alt=\"")
                .append(escapeAttribute(alt)).append('"')
            if (title != null) img.append(" title=\"").append(escapeAttribute(title)).append('"')
            img.append('>')
            drafts += Draft(
                kind = BlockKind.IMAGE,
                start = start,
                plainText = alt,
                context = ctx,
                content = BlockContent.Html(HtmlFragment.parse(img.toString(), allowRemoteImages = true)),
                target = target,
                title = title,
                extraKey = "image=$target",
            )
        }

        private fun thematicBreak(node: StructuralNode, ctx: BlockContext, page: Boolean) {
            val start = startOf(node)
            drafts += Draft(
                kind = BlockKind.THEMATIC_BREAK,
                start = start,
                fixedEnd = start + 1,
                sourceText = lines.sourceLine(start).trimEnd(),
                plainText = "",
                context = ctx,
                extraKey = if (page) "page-break" else "thematic-break",
            )
        }

        private fun pass(node: AstBlock, ctx: BlockContext) {
            val start = startOf(node)
            addGroup(BlockGroupKind.CODE, start)
            val html = node.lines.orEmpty().joinToString("\n")
            val parts = HtmlFragment.parse(html, allowRemoteImages = true)
            drafts += Draft(
                kind = BlockKind.HTML,
                start = start,
                sourceText = html,
                plainText = HtmlFragment.visibleText(parts),
                context = ctx,
                content = BlockContent.Html(parts),
            )
        }

        // ---- source lines -----------------------------------------------------------------------------------

        private fun startOf(node: StructuralNode): Int {
            var line = (node.sourceLocation?.lineNumber ?: 0) - 1
            if (line < 0) line = lastStart
            if (line in lines.directiveLines) {
                line = anchorConditional(node, line) ?: line
            }
            return startAt(maxOf(line, lastStart))
        }

        private fun startAt(line: Int): Int {
            lastStart = maxOf(lastStart, line.coerceAtLeast(0))
            return lastStart
        }

        /**
         * P4.1 found that a paragraph directly before `endif::[]` is reported on the `endif` line. Find its own
         * source text above the directive, or fall back to the previous non-blank line.
         */
        private fun anchorConditional(node: StructuralNode, line: Int): Int? {
            val source = (node as? AstBlock)?.let { block ->
                block.lines?.firstOrNull() ?: block.source?.lineSequence()?.firstOrNull()
            }
            val wanted = source?.trim().orEmpty()
            if (wanted.isNotEmpty()) {
                for (i in line - 1 downTo maxOf(0, line - 20)) {
                    if (lines.sourceLine(i).trim() == wanted) return i
                }
            }
            for (i in line - 1 downTo 0) {
                val candidate = lines.sourceLine(i)
                if (candidate.isNotBlank()) return i
            }
            return null
        }

        private fun isSourceAttribute(line: String): Boolean {
            val trimmed = line.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false
            val style = trimmed.removeSurrounding("[", "]").substringBefore(',').trim()
            return style in setOf("source", "listing", "literal")
        }

        // ---- merge and materialize --------------------------------------------------------------------------

        private fun finish(): List<Block> {
            val sorted = drafts.sortedBy { it.start }
            resolveTitleContexts(sorted)
            for (i in sorted.indices) {
                val draft = sorted[i]
                val limit = sorted.getOrNull(i + 1)?.start ?: lines.lines.size
                var end = draft.fixedEnd ?: endLine(draft.start, limit, draft.minimumEnd)
                if (end > lines.lines.size) end = lines.lines.size
                if (end <= draft.start) end = draft.start + 1
                draft.resolvedEnd = end
            }
            val allGroups = groups + listItemGroups()
            val orderedGroups = allGroups.sortedWith(
                compareBy<BlockGroup> { it.startLine }.thenByDescending { it.endLine },
            )
            val blocks = ArrayList<Block>(sorted.size)
            for (draft in sorted) {
                val own = orderedGroups.filter { draft.start >= it.startLine && draft.start < it.endLine }
                blocks += materialize(draft, draft.resolvedEnd, own)
            }
            return blocks
        }

        /**
         * The range of every list item with attached blocks: from the item's prefix lines through the last block
         * of its nested list or `+` continuation, including the closing delimiter of an attached code block.
         */
        private fun listItemGroups(): List<BlockGroup> = itemGroups.mapNotNull { item ->
            val itemDraft = item.item
            val members = item.members
            val first = lines.editPrefixBefore(itemDraft.start).firstOrNull() ?: itemDraft.start
            val end = members.maxOf { member ->
                groups.filter { member.start >= it.startLine && member.start < it.endLine }
                    .maxOfOrNull { it.endLine } ?: member.resolvedEnd
            }
            if (end <= first) null else BlockGroup(
                kind = BlockGroupKind.LIST_ITEM,
                startLine = first,
                endLine = end,
                openLine = itemDraft.start,
            )
        }

        /**
         * A `.Title` is a block of its own but sits inside the frame of the container it titles; give it the
         * containers of the block it belongs to, without list or description/callout wrappers.
         */
        private fun resolveTitleContexts(sorted: List<Draft>) {
            for (i in sorted.indices) {
                val title = sorted[i]
                if (title.kind != BlockKind.BLOCK_TITLE || !title.scanner) continue
                val next = sorted.drop(i + 1).firstOrNull { it.kind != BlockKind.BLOCK_TITLE } ?: continue
                val containers = next.context.containers.filterNot { it.kind == "description" || it.kind == "callout" }
                title.context = next.context.copy(
                    quoteDepth = 0,
                    listDepth = 0,
                    ordered = false,
                    number = null,
                    alert = null,
                    details = 0,
                    containers = containers,
                )
            }
        }

        private fun endLine(start: Int, limit: Int, minimumEnd: Int?): Int {
            val floor = maxOf(start + 1, minimumEnd ?: (start + 1))
            var end = maxOf(floor, limit)
            while (end > floor && trimmable(end - 1)) end--
            return end
        }

        /** Blank lines, delimiters and the block's own prefix lines are outside its display range. */
        private fun trimmable(line: Int): Boolean {
            val text = lines.sourceLine(line)
            return text.isBlank() || line in lines.delimiterLines || line in lines.prefixLines || text.trim() == "+"
        }

        private fun materialize(draft: Draft, end: Int, editGroups: List<BlockGroup>): Block {
            val sourceText = draft.sourceText ?: lines.normalizedSource(draft.start, end)
            val key = buildString {
                if (draft.tableHeader) append("th:") else if (draft.kind == BlockKind.TABLE_ROW) append("td:")
                append(draft.kind.name).append(':').append(contextKey(draft.context, draft.extraKey)).append(':')
                append(normalizeSource(sourceText)).append('\u0000').append(draft.plainText.trim())
            }
            return Block(
                kind = draft.kind,
                key = key,
                plainText = draft.plainText,
                rawText = sourceText,
                source = lines.range(draft.start, end),
                context = draft.context,
                content = draft.content,
                language = draft.language,
                level = draft.level,
                target = draft.target,
                title = draft.title,
                prefixLines = if (draft.kind == BlockKind.BLOCK_TITLE) emptyList() else lines.editPrefixBefore(draft.start),
                editGroups = editGroups,
            )
        }

        private fun contextKey(ctx: BlockContext, extra: String): String {
            val parts = mutableListOf<String>()
            ctx.containers.forEach { container ->
                parts += "c:" + container.kind + (container.type?.let { "/$it" } ?: "")
            }
            if (ctx.listDepth > 0) parts += "l" + ctx.listDepth
            if (ctx.ordered) parts += "ol"
            if (extra.isNotEmpty()) parts += extra
            return if (parts.isEmpty()) "-" else parts.joinToString(",")
        }

        private fun textContent(text: String): BlockContent =
            BlockContent.Html(listOf(HtmlFragment.TextRun(text)))

        private fun escapeAttribute(value: String): String =
            value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    }

    private class Draft(
        val kind: BlockKind,
        val start: Int,
        var fixedEnd: Int? = null,
        var sourceText: String? = null,
        var plainText: String,
        var context: BlockContext = BlockContext(),
        var content: BlockContent = BlockContent.Raw,
        var language: String? = null,
        var level: Int = 0,
        var target: String? = null,
        var title: String? = null,
        var extraKey: String = "",
        var tableHeader: Boolean = false,
        var scanner: Boolean = false,
        var minimumEnd: Int? = null,
        var resolvedEnd: Int = 0,
    )

    /** A list item and every draft its children produced, for the item's edit group. */
    private class ItemGroupDraft(val item: Draft, val members: List<Draft>)

    private companion object {
        val WHITESPACE = Regex("\\s+")

        fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

        fun normalizeSource(text: String): String =
            text.split('\n').joinToString("\n") { it.trimEnd() }.trimEnd()
    }
}
