package pl.mdreview.engine

import org.commonmark.node.Block as MarkdownBlock
import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState

/** An inline formula, `$...$` (or `$$...$$` inside a paragraph); [literal] is its TeX source without the dollars. */
class InlineMath(val literal: String, val display: Boolean = false) : CustomNode()

/** A block formula: `$$` on a line of its own, the TeX source, and a closing `$$`. */
class MathBlock : CustomBlock() {
    var literal: String = ""
}

/**
 * Math as on GitHub: `$...$` inline and `$$...$$` blocks become [InlineMath] and [MathBlock] nodes, so a formula is
 * compared and rendered as one unit. commonmark-java has no math extension of its own.
 *
 * An inline formula opens with a `$` not followed by whitespace and closes with the next `$` not preceded by
 * whitespace and not followed by a digit, so prices such as "$5 or $10" stay text. Code spans and backslash
 * escapes (`\$`) are parsed before, as for any inline.
 */
class MathExtension private constructor() : Parser.ParserExtension {

    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customBlockParserFactory(MathBlockParser.Factory())
        parserBuilder.customInlineContentParserFactory(InlineMathParser.Factory())
    }

    companion object {
        fun create(): MathExtension = MathExtension()
    }
}

private class InlineMathParser : InlineContentParser {

    override fun tryParse(state: InlineParserState): ParsedInline? {
        val scanner = state.scanner()
        val start = scanner.position()
        val dollars = scanner.matchMultiple('$')
        val display = dollars == 2
        val content = StringBuilder()
        if (dollars > 2 || !scanner.hasNext() || scanner.peek().isWhitespace()) {
            scanner.setPosition(start)
            return ParsedInline.none()
        }
        while (scanner.hasNext()) {
            val c = scanner.peek()
            if (c == '$') {
                val previous = content.lastOrNull()
                val closeStart = scanner.position()
                val closing = scanner.matchMultiple('$')
                val next = if (scanner.hasNext()) scanner.peek() else null
                if (closing == dollars && previous != null && !previous.isWhitespace() && next?.isDigit() != true) {
                    return ParsedInline.of(InlineMath(content.toString(), display), scanner.position())
                }
                // Not a closing delimiter: the dollars are part of the formula.
                content.append(scanner.getSource(closeStart, scanner.position()).content)
                continue
            }
            // A formula stays within its paragraph; a blank line already ended it, a line break is a space in TeX.
            content.append(if (c == '\n') ' ' else c)
            scanner.next()
        }
        scanner.setPosition(start)
        return ParsedInline.none()
    }

    class Factory : InlineContentParserFactory {
        override fun getTriggerCharacters(): Set<Char> = setOf('$')
        override fun create(): InlineContentParser = InlineMathParser()
    }
}

private class MathBlockParser : AbstractBlockParser() {

    private val block = MathBlock()
    private val lines = mutableListOf<String>()
    private var opening = true

    override fun getBlock(): MarkdownBlock = block

    override fun tryContinue(state: ParserState): BlockContinue? {
        val line = state.line.content
        val text = line.substring(state.nextNonSpaceIndex).trimEnd()
        if (state.indent < 4 && text.endsWith(DELIMITER)) {
            val before = text.removeSuffix(DELIMITER)
            if (before.isNotBlank()) lines += before.trimEnd()
            return BlockContinue.finished()
        }
        return BlockContinue.atIndex(state.index)
    }

    override fun addLine(line: SourceLine) {
        val text = line.content.toString()
        // The first line is what follows the opening `$$`, usually nothing.
        if (opening) {
            opening = false
            if (text.isNotBlank()) lines += text.trim()
        } else {
            lines += text
        }
    }

    override fun closeBlock() {
        block.literal = lines.joinToString("\n")
    }

    class Factory : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
            if (state.indent >= 4) return BlockStart.none()
            // A formula does not interrupt a paragraph, like a fenced code block in most renderers.
            if (matchedBlockParser.paragraphLines.lines.isNotEmpty()) return BlockStart.none()
            val line = state.line.content
            val start = state.nextNonSpaceIndex
            val text = line.substring(start).trimEnd()
            if (!text.startsWith(DELIMITER) || text.startsWith("$$$")) return BlockStart.none()
            // `$$ x $$` on one line is a formula inside a paragraph, see InlineMathParser.
            if (text.removePrefix(DELIMITER).endsWith(DELIMITER)) return BlockStart.none()
            return BlockStart.of(MathBlockParser()).atIndex(start + DELIMITER.length)
        }
    }

    companion object {
        private const val DELIMITER = "$$"
    }
}
