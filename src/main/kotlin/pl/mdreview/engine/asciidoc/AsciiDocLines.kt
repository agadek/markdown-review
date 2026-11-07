package pl.mdreview.engine.asciidoc

import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.Container
import pl.mdreview.engine.SourceRange

/**
 * A line scanner over an AsciiDoc source, used together with Asciidoctor's tree.
 *
 * Asciidoctor's source map gives the first line of structural nodes but no end lines, and nothing at all for the
 * document header, comments, attribute entries, `include::` lines and preprocessor directives. This pass finds those
 * lines, matches delimiter lines with their closing line, and computes the line ranges of tree blocks: the line before
 * the next block or the enclosing close delimiter, minus trailing blank, delimiter and prefix lines.
 *
 * Platform-free: no AsciidoctorJ or IntelliJ APIs.
 */
internal class AsciiDocLines(private val text: String) {

    /** One line of the source; the last element is empty when the text ends with a newline. */
    val lines: List<String> = text.split('\n')

    private val lineStarts = IntArray(lines.size + 1).also { starts ->
        var offset = 0
        for (i in lines.indices) {
            starts[i] = offset
            offset += lines[i].length + 1
        }
        starts[lines.size] = offset
    }

    /** A scanner-produced row, in source order. [endLine] is exclusive; null lets the merge assign it. */
    class Row(
        val kind: BlockKind,
        val startLine: Int,
        val endLine: Int? = null,
        val rawText: String,
        val plainText: String,
        val target: String? = null,
        val containers: List<Container> = emptyList(),
        val level: Int = 0,
    )

    /** A delimiter line and what it opens or closes. */
    class Delimiter(
        val family: Char,
        val length: Int,
        val kind: String,
        val startLine: Int,
        val closeLine: Int? = null,
        val container: Container? = null,
    )

    val rows = mutableListOf<Row>()

    /** Every opening and closing delimiter line. */
    val delimiterLines = mutableSetOf<Int>()

    /** Lines that belong to a block's metadata rather than its visible content: `[source]`, `[[id]]`, `+`, ... */
    val prefixLines = mutableSetOf<Int>()

    /** Prefix lines directly above a content line, keyed by that line, in source order. */
    private val prefixBefore = mutableMapOf<Int, List<Int>>()

    /** Lines of `include::` directives, so the tree's SECURE-mode placeholder paragraph can be dropped. */
    val includeLines = mutableSetOf<Int>()

    /** Lines of `ifdef`/`ifndef`/`ifeval`/`endif` directives. */
    val directiveLines = mutableSetOf<Int>()

    /** Index of the document title line, or null. */
    val titleLine: Int?

    /** First line after the document header (the blank line that ends it, or the start of the body). */
    val headerEnd: Int

    private val openDelimiters = mutableListOf<Delimiter>()
    private val delimitersByStart = mutableMapOf<Int, Delimiter>()
    private val delimitersByClose = mutableMapOf<Int, Delimiter>()

    init {
        titleLine = findTitleLine()
        if (titleLine == null) {
            headerEnd = 0
            scan(0, lines.size)
        } else {
            scan(0, titleLine)
            emitTitle(titleLine)
            headerEnd = emitHeader(titleLine)
            scan(headerEnd, lines.size)
        }
    }

    /** The scanner row of the document title, or null when the document has none. */
    fun titleRow(): Row? = titleLine?.let { line ->
        rows.firstOrNull { it.startLine == line && it.kind == BlockKind.HEADING }
    }

    /** Prefix lines directly above [line], for example `[source,java]` before a listing's `----`. */
    fun prefixesBefore(line: Int): List<Int> = prefixBefore[line].orEmpty()

    fun openingDelimiter(line: Int): Delimiter? = delimitersByStart[line]

    fun closingDelimiter(line: Int): Delimiter? = delimitersByClose[line]

    /** The line of the enclosing close delimiter for [line], or null when none encloses it. */
    fun enclosingClose(line: Int): Int? = openDelimiters.lastOrNull { it.startLine < line }?.let { delimiter ->
        delimitersByStart[delimiter.startLine]?.closeLine
    }

    fun source(startLine: Int, endLine: Int): String {
        if (endLine <= startLine) return ""
        val from = startLine.coerceIn(0, lines.size)
        val to = endLine.coerceIn(from, lines.size)
        return lines.subList(from, to).joinToString("\n")
    }

    fun sourceLine(line: Int): String = lines.getOrElse(line) { "" }

    fun range(startLine: Int, endLine: Int): SourceRange {
        val start = lineStarts[startLine.coerceIn(0, lines.size)]
        val end = lineStarts[endLine.coerceIn(startLine.coerceIn(0, lines.size), lines.size)]
        return SourceRange(startLine, endLine, start, end)
    }

    /** The block's own source with trailing whitespace removed on every line, as the block key uses it. */
    fun normalizedSource(startLine: Int, endLine: Int): String =
        source(startLine, endLine).split('\n').joinToString("\n") { it.trimEnd() }.trimEnd()

    // ---- scanning ---------------------------------------------------------------------------------------------

    private fun scan(from: Int, to: Int) {
        val stack = openDelimiters
        val pending = mutableListOf<Int>()
        var i = from
        while (i < to) {
            val line = lines[i]
            val top = stack.lastOrNull()

            if (top != null && top.opaque()) {
                if (closes(line, top)) {
                    closeDelimiter(top, i)
                    if (top.kind == "comment") emitBlockComment(top.startLine, i)
                    pending.clear()
                }
                i++
                continue
            }

            if (line.isBlank()) {
                pending.clear()
                i++
                continue
            }

            if (top != null && closes(line, top)) {
                closeDelimiter(top, i)
                pending.clear()
                i++
                continue
            }

            val open = delimiter(line)
            if (open != null) {
                val familyTop = stack.lastOrNull()
                if (familyTop != null && familyTop.family == open.family && open.length < familyTop.length) {
                    // A shorter run of the same delimiter character is content, not a new block.
                    i++
                    continue
                }
                attachPrefixes(i, pending)
                openDelimiter(open, i, pending)
                pending.clear()
                i++
                continue
            }

            if (isBlockComment(line)) {
                attachPrefixes(i, pending)
                delimiterLines += i
                stack += Delimiter('/', 4, "comment", i)
                pending.clear()
                i++
                continue
            }

            if (isLineComment(line)) {
                attachPrefixes(i, pending)
                rows += Row(
                    kind = BlockKind.HTML_COMMENT,
                    startLine = i,
                    endLine = i + 1,
                    rawText = line,
                    plainText = line.removePrefix("//").trim(),
                    containers = containersAt(stack),
                )
                pending.clear()
                i++
                continue
            }

            if (isPrefix(line)) {
                prefixLines += i
                pending += i
                i++
                continue
            }

            if (isBlockTitle(line)) {
                rows += Row(
                    kind = BlockKind.BLOCK_TITLE,
                    startLine = i,
                    endLine = i + 1,
                    rawText = line,
                    plainText = line.substring(1).trim(),
                )
                // A pending anchor belongs to the block the title belongs to, not to the title row.
                i++
                continue
            }

            if (isAttribute(line)) {
                attachPrefixes(i, pending)
                rows += Row(
                    kind = BlockKind.ATTRIBUTE,
                    startLine = i,
                    endLine = i + 1,
                    rawText = line,
                    plainText = line.trimEnd(),
                    containers = containersAt(stack),
                )
                pending.clear()
                i++
                continue
            }

            if (isDirective(line)) {
                attachPrefixes(i, pending)
                directiveLines += i
                rows += Row(
                    kind = BlockKind.HTML_COMMENT,
                    startLine = i,
                    endLine = i + 1,
                    rawText = line,
                    plainText = line.trimEnd(),
                    containers = containersAt(stack),
                )
                pending.clear()
                i++
                continue
            }

            val include = includeTarget(line)
            if (include != null) {
                attachPrefixes(i, pending)
                includeLines += i
                rows += Row(
                    kind = BlockKind.INCLUDE,
                    startLine = i,
                    endLine = i + 1,
                    rawText = line,
                    plainText = line.trimEnd(),
                    target = include,
                    containers = containersAt(stack),
                )
                pending.clear()
                i++
                continue
            }

            attachPrefixes(i, pending)
            pending.clear()
            i++
        }
        // An unclosed comment still shows something; unclosed containers keep their open line.
        stack.filter { it.kind == "comment" }.forEach { emitBlockComment(it.startLine, lines.size - 1) }
    }

    private fun emitTitle(line: Int) {
        val text = lines[line].removePrefix("=").trim()
        rows += Row(
            kind = BlockKind.HEADING,
            startLine = line,
            endLine = line + 1,
            rawText = lines[line],
            plainText = text,
            level = 1,
        )
    }

    /** Emits the author, revision and attribute lines of the header; returns the first body line. */
    private fun emitHeader(titleLine: Int): Int {
        var i = titleLine + 1
        while (i < lines.size && lines[i].isNotBlank()) {
            val line = lines[i]
            if (line.trimStart().startsWith("//")) break
            rows += Row(
                kind = BlockKind.ATTRIBUTE,
                startLine = i,
                endLine = i + 1,
                rawText = line,
                plainText = line.trimEnd(),
            )
            i++
        }
        return i
    }

    private fun findTitleLine(): Int? {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            if (isBlockComment(line)) {
                i++
                while (i < lines.size && !closes(lines[i], Delimiter('/', 4, "comment", 0))) i++
                i++
                continue
            }
            if (isLineComment(line)) {
                i++
                continue
            }
            return if (line.startsWith("= ") || line == "=") i else null
        }
        return null
    }

    private fun attachPrefixes(contentLine: Int, pending: List<Int>) {
        if (pending.isEmpty()) return
        prefixBefore[contentLine] = prefixBefore[contentLine].orEmpty() + pending
    }

    private fun openDelimiter(open: Delimiter, line: Int, pending: List<Int>) {
        val style = pending.asReversed().firstNotNullOfOrNull { blockStyle(lines[it]) }
        val kind = when {
            open.kind == "example" && style != null && style in ADMONITION_TYPES -> "admonition"
            open.kind == "quote" && style == "verse" -> "verse"
            else -> open.kind
        }
        val attribution = pending.asReversed().firstNotNullOfOrNull { blockAttribution(lines[it], kind) }
        val container = when (kind) {
            "example", "sidebar", "open" -> Container(kind, title = lastTitleBefore(line))
            "quote", "verse" -> Container(kind, title = attribution)
            "admonition" -> Container(
                "admonition",
                type = style?.uppercase(),
                title = lastTitleBefore(line),
            )
            else -> null
        }
        val delimiter = Delimiter(open.family, open.length, kind, line, container = container)
        openDelimiters += delimiter
        delimitersByStart[line] = delimiter
        delimiterLines += line
    }

    private fun closeDelimiter(open: Delimiter, line: Int) {
        delimiterLines += line
        delimitersByClose[line] = Delimiter(
            family = open.family,
            length = open.length,
            kind = open.kind,
            startLine = open.startLine,
            closeLine = line,
            container = open.container,
        )
        val closed = Delimiter(
            family = open.family,
            length = open.length,
            kind = open.kind,
            startLine = open.startLine,
            closeLine = line,
            container = open.container,
        )
        delimitersByStart[open.startLine] = closed
        openDelimiters.remove(open)
    }

    private fun emitBlockComment(start: Int, close: Int) {
        val inner = if (close > start) lines.subList(start + 1, close) else emptyList()
        rows += Row(
            kind = BlockKind.HTML_COMMENT,
            startLine = start,
            endLine = (close + 1).coerceAtMost(lines.size),
            rawText = source(start, (close + 1).coerceAtMost(lines.size)),
            plainText = inner.joinToString("\n") { it.trimEnd() }.trimEnd(),
        )
    }

    private fun lastTitleBefore(line: Int): String? {
        val title = rows.lastOrNull { it.kind == BlockKind.BLOCK_TITLE && it.startLine < line } ?: return null
        for (i in title.startLine + 1 until line) {
            val candidate = lines[i]
            if (candidate.isNotBlank() && !isPrefix(candidate) && !isLineComment(candidate)) return null
        }
        return title.plainText
    }

    private fun containersAt(stack: List<Delimiter>): List<Container> =
        stack.mapNotNull { it.container }

    // ---- delimiter recognition --------------------------------------------------------------------------------

    private fun delimiter(line: String): Delimiter? {
        if (line.isEmpty() || line.first().isWhitespace()) return null
        val trimmed = line.trimEnd()
        if (trimmed == "--") return Delimiter('-', 2, "open", 0)
        if (trimmed.startsWith("|===")) {
            val equals = trimmed.drop(1).takeWhile { it == '=' }.length
            if (equals >= 3 && equals == trimmed.length - 1) {
                return Delimiter('|', trimmed.length, "table", 0)
            }
            return null
        }
        val head = trimmed.first()
        val length = trimmed.takeWhile { it == head }.length
        if (length != trimmed.length || length < 4) return null
        val kind = when (head) {
            '=' -> "example"
            '*' -> "sidebar"
            '_' -> "quote"
            '-' -> "listing"
            '.' -> "literal"
            '+' -> "pass"
            '/' -> "comment"
            else -> return null
        }
        return Delimiter(head, length, kind, 0)
    }

    private fun closes(line: String, open: Delimiter): Boolean {
        val first = line.firstOrNull() ?: return false
        if (first.isWhitespace()) return false
        val trimmed = line.trimEnd()
        if (open.family == '-' && open.length == 2) return trimmed == "--"
        if (open.family == '|') {
            if (!trimmed.startsWith("|===")) return false
            return trimmed.drop(1).all { it == '=' } && trimmed.length >= open.length
        }
        val head = trimmed.firstOrNull() ?: return false
        if (head != open.family) return false
        val length = trimmed.takeWhile { it == head }.length
        return length == trimmed.length && length >= open.length
    }

    private fun Delimiter.opaque(): Boolean = kind in OPAQUE

    private fun isBlockComment(line: String): Boolean = line.startsWith("////") && line.all { it == '/' }

    private fun isLineComment(line: String): Boolean = line.startsWith("//")

    private fun isPrefix(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed == "+") return true
        if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) return true
        return trimmed.startsWith("[") && trimmed.endsWith("]")
    }

    private fun isBlockTitle(line: String): Boolean =
        line.length > 1 && line[0] == '.' && !line[1].isWhitespace() && line[1] != '.'

    private fun isAttribute(line: String): Boolean = attribute.matchEntire(line) != null

    private fun isDirective(line: String): Boolean = DIRECTIVE.containsMatchIn(line)

    private fun includeTarget(line: String): String? {
        val match = INCLUDE.matchEntire(line) ?: return null
        return match.groupValues[1].trim()
    }

    private fun blockStyle(line: String): String? {
        val content = line.trim().removeSurrounding("[", "]").ifBlank { return null }
        return content.substringBefore(',').trim().ifBlank { null }
    }

    private fun blockAttribution(line: String, kind: String): String? {
        if (kind != "quote" && kind != "verse") return null
        val content = line.trim().removeSurrounding("[", "]")
        val parts = content.split(',')
        if (parts.size < 2) return null
        return parts.drop(1).joinToString(",").trim().ifBlank { null }
    }

    private companion object {
        val ADMONITION_TYPES = setOf("NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION")
        val OPAQUE = setOf("listing", "literal", "pass", "comment", "table")
        val attribute = Regex("^:([^:\\s]+):(?:[ \\t]*(.*))?$")
        val DIRECTIVE = Regex("^(ifdef|ifndef|ifeval|endif)::.*$")
        val INCLUDE = Regex("^include::([^\\[]*)\\[.*]$")
    }
}
