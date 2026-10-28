package pl.mdreview.engine

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SourceSpan
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor

/**
 * Wiki links as in Obsidian, Foam and MediaWiki: `[[page]]`, `[[page|label]]` and `[[page#section]]`
 * become ordinary [Link] nodes, so a changed label shows in the word diff and a changed target is marked like any
 * other changed link. The target comes first and the label second; GitHub wikis write `[[label|page]]`, which reads
 * the same in the rendered view but links to the label.
 *
 * The link's destination is the target as written, with spaces as `%20`. Text inside links and code, and text with a
 * backslash escape or an entity (such as `\[[`), stays as written. Emoji shortcodes in a label still turn into emoji:
 * register this extension before [EmojiExtension], which changes the text this one maps back to its source.
 */
class WikiLinkExtension private constructor() : Parser.ParserExtension {

    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.postProcessor(Replacer)
    }

    private object Replacer : PostProcessor {
        override fun process(node: Node): Node {
            val texts = mutableListOf<Text>()
            node.accept(object : AbstractVisitor() {
                override fun visit(link: Link) = Unit

                override fun visit(image: Image) = Unit

                override fun visit(code: Code) = Unit

                override fun visit(text: Text) {
                    if (text.literal.contains("[[")) texts += text
                }
            })
            texts.forEach(::replace)
            return node
        }

        private fun replace(text: Text) {
            val literal = text.literal
            // commonmark drops the backslash of `\[[` from the literal, so only a source as long as the literal tells
            // that nothing was escaped. Text with an escape or an entity stays as written.
            val span = text.sourceSpans.singleOrNull()?.takeIf { it.length == literal.length } ?: return
            val matches = pattern.findAll(literal).filter { it.groupValues[1].trim() != GITLAB_TOC }.toList()
            if (matches.isEmpty()) return
            fun spanOf(start: Int, end: Int): List<SourceSpan> =
                listOf(SourceSpan.of(span.lineIndex, span.columnIndex + start, span.inputIndex + start, end - start))

            var at = 0
            for (match in matches) {
                if (match.range.first > at) text.insertBefore(textNode(literal.substring(at, match.range.first), spanOf(at, match.range.first)))
                val target = match.groupValues[1].trim()
                val label = match.groupValues[2].trim().ifEmpty { target }
                val link = Link(destination(target), null)
                link.sourceSpans = spanOf(match.range.first, match.range.last + 1)
                link.appendChild(textNode(label, link.sourceSpans))
                text.insertBefore(link)
                at = match.range.last + 1
            }
            if (at < literal.length) text.insertBefore(textNode(literal.substring(at), spanOf(at, literal.length)))
            text.unlink()
        }

        private fun textNode(literal: String, spans: List<SourceSpan>) = Text(literal).apply { sourceSpans = spans }
    }

    companion object {
        fun create(): WikiLinkExtension = WikiLinkExtension()

        /**
         * `[[target]]` or `[[target|label]]`, on one line, without brackets inside. An Obsidian embed (`![[image.png]]`)
         * is not a link and stays as written, and so does GitLab's table of contents marker, `[[TOC]]`
         * (its `[[_TOC_]]` spelling never reaches here: the underscores make it emphasis).
         */
        private val pattern = Regex("""(?<!!)\[\[([^\[\]|\n]*[^\[\]|\s][^\[\]|\n]*)(?:\|([^\[\]\n]*))?]]""")

        private const val GITLAB_TOC = "TOC"

        /** [target] as a link destination: spaces encoded, so the HTML renderer keeps it as one URL. */
        fun destination(target: String): String = target.replace(" ", "%20")
    }
}
