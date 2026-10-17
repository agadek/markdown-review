package pl.mdreview.engine

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor

/**
 * Turns GitHub emoji shortcodes (`:smile:`) in text into Unicode emoji, from the table in `emoji.txt`, so a
 * changed shortcode is a changed emoji in the word diff. Unknown shortcodes and code stay as written.
 */
class EmojiExtension private constructor() : Parser.ParserExtension {

    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.postProcessor(Replacer)
    }

    private object Replacer : PostProcessor {
        override fun process(node: Node): Node {
            node.accept(object : AbstractVisitor() {
                override fun visit(text: Text) {
                    val literal = text.literal
                    if (literal.indexOf(':') != literal.lastIndexOf(':')) text.literal = replace(literal)
                }
            })
            return node
        }
    }

    companion object {
        fun create(): EmojiExtension = EmojiExtension()

        private val shortcode = Regex("(?<![\\p{L}\\p{N}]):([a-z0-9_+-]+):")

        val table: Map<String, String> by lazy {
            val stream = EmojiExtension::class.java.getResourceAsStream("/pl/mdreview/emoji.txt")
                ?: error("Missing resource /pl/mdreview/emoji.txt")
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith("#") }
                    .associate { it.substringBefore(' ') to it.substringAfter(' ').trim() }
            }
        }

        /** [text] with every known shortcode replaced by its emoji. */
        fun replace(text: String): String = shortcode.replace(text) { table[it.groupValues[1]] ?: it.value }
    }
}
