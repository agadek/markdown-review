package pl.mdreview.render

import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.node.Code
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.InlineSegment

/**
 * Word-level marks for one side of a modified block, expressed as offsets into the block's plain
 * text. [marks] wrap existing text; [insertions] put text from the other side at a position
 * (used by the merged view to show removed words inline).
 */
class InlineMarks(
    val marks: List<Mark> = emptyList(),
    val insertions: List<Insertion> = emptyList(),
) {
    data class Mark(val start: Int, val end: Int, val tag: String)
    data class Insertion(val offset: Int, val text: String, val tag: String)

    companion object {
        // Written as <del class="mdr-del"> / <ins class="mdr-ins">; the class tells them apart from
        // <del> produced by ~~strikethrough~~ in the content.
        const val DEL = "del"
        const val INS = "ins"

        val NONE = InlineMarks()

        fun left(segments: List<InlineSegment>): InlineMarks {
            val marks = mutableListOf<Mark>()
            var pos = 0
            for (s in segments) {
                when (s.status) {
                    DiffStatus.EQUAL -> pos += s.text.length
                    DiffStatus.REMOVED -> { marks += Mark(pos, pos + s.text.length, DEL); pos += s.text.length }
                    else -> Unit
                }
            }
            return InlineMarks(marks)
        }

        fun right(segments: List<InlineSegment>, withRemoved: Boolean = false): InlineMarks {
            val marks = mutableListOf<Mark>()
            val insertions = mutableListOf<Insertion>()
            var pos = 0
            for (s in segments) {
                when (s.status) {
                    DiffStatus.EQUAL -> pos += s.text.length
                    DiffStatus.ADDED -> { marks += Mark(pos, pos + s.text.length, INS); pos += s.text.length }
                    DiffStatus.REMOVED -> if (withRemoved) insertions += Insertion(pos, s.text, DEL)
                    DiffStatus.MODIFIED -> Unit
                }
            }
            return InlineMarks(marks, insertions)
        }
    }
}

/**
 * Writes plain text with word marks applied. One instance renders one block; [offsets] maps each
 * [Text]/[Code] node to its position in the block's plain text.
 */
class MarkedTextWriter(private val marks: InlineMarks, private val escape: (String) -> String) {
    private val pending = ArrayDeque(marks.insertions.sortedBy { it.offset })

    /** Text of [literal] starting at plain-text [offset], as HTML. */
    fun write(offset: Int, literal: String): String {
        val sb = StringBuilder()
        flushInsertions(sb, offset)
        val end = offset + literal.length
        val cuts = sortedSetOf(offset, end)
        for (m in marks.marks) {
            if (m.end > offset && m.start < end) {
                cuts += m.start.coerceIn(offset, end)
                cuts += m.end.coerceIn(offset, end)
            }
        }
        pending.filter { it.offset in (offset + 1) until end }.forEach { cuts += it.offset }
        val points = cuts.toList()
        for (k in 0 until points.size - 1) {
            val a = points[k]
            val b = points[k + 1]
            flushInsertions(sb, a)
            val chunk = escape(literal.substring(a - offset, b - offset))
            val tag = marks.marks.firstOrNull { it.start <= a && it.end >= b }?.tag
            if (tag != null) sb.append(open(tag)).append(chunk).append("</").append(tag).append('>')
            else sb.append(chunk)
        }
        return sb.toString()
    }

    /**
     * A marker for the hard line break at plain-text [offset] when the word diff marks it (an added or removed
     * break), otherwise nothing. The caller writes the `<br>` itself.
     */
    fun lineBreak(offset: Int): String {
        val sb = StringBuilder()
        flushInsertions(sb, offset)
        val tag = marks.marks.firstOrNull { it.start <= offset && it.end > offset }?.tag
        if (tag != null) sb.append(breakMarker(tag))
        return sb.toString()
    }

    /** Insertions left after the last text node (for example words removed at the end of a block). */
    fun rest(): String {
        val sb = StringBuilder()
        flushInsertions(sb, Int.MAX_VALUE)
        return sb.toString()
    }

    private fun open(tag: String) = "<$tag class=\"mdr-$tag\">"

    private fun breakMarker(tag: String) = "<$tag class=\"mdr-$tag mdr-ws mdr-ws-break\"></$tag>"

    private fun flushInsertions(sb: StringBuilder, upTo: Int) {
        while (pending.isNotEmpty() && pending.first().offset <= upTo) {
            val ins = pending.removeFirst()
            // A removed hard line break shows as a marker, not as invisible whitespace.
            if (ins.text.isBlank() && '\n' in ins.text) sb.append(breakMarker(ins.tag))
            else sb.append(open(ins.tag)).append(escape(ins.text)).append("</").append(ins.tag).append('>')
        }
    }
}

/** commonmark renderer for [Text] and [Code] that routes text through a [MarkedTextWriter]. */
internal class MarkedTextNodeRenderer(
    private val context: HtmlNodeRendererContext,
    private val writer: MarkedTextWriter,
    private val offsets: Map<Node, Int>,
) : NodeRenderer {

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(Text::class.java, Code::class.java)

    override fun render(node: Node) {
        val html = context.writer
        when (node) {
            is Text -> html.raw(writer.write(offsets[node] ?: return html.text(node.literal), node.literal))
            is Code -> {
                html.raw("<code>")
                val offset = offsets[node]
                if (offset == null) html.text(node.literal) else html.raw(writer.write(offset, node.literal))
                html.raw("</code>")
            }
        }
    }
}

/**
 * commonmark renderer for [FootnoteReference]: a superscript link to the definition on the same side.
 * The plain text holds the reference as `[^label]`; only the label is shown, with its word marks.
 */
internal class FootnoteReferenceRenderer(
    private val context: HtmlNodeRendererContext,
    private val writer: MarkedTextWriter,
    private val offsets: Map<Node, Int>,
    private val anchor: (String) -> String,
) : NodeRenderer {

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(FootnoteReference::class.java)

    override fun render(node: Node) {
        val ref = node as FootnoteReference
        val html = context.writer
        html.raw("<sup class=\"mdr-fnref\"><a href=\"#" + anchor(ref.label) + "\">")
        val offset = offsets[ref]
        if (offset == null) html.text(ref.label) else html.raw(writer.write(offset + 2, ref.label))
        html.raw("</a></sup>")
    }
}

/**
 * commonmark renderer for [HardLineBreak]: the `<br>`, preceded by a ↵ marker when the break itself was added or
 * removed (`mdr-ws mdr-ws-break`).
 */
internal class HardBreakRenderer(
    private val context: HtmlNodeRendererContext,
    private val writer: MarkedTextWriter,
    private val offsets: Map<Node, Int>,
) : NodeRenderer {

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(HardLineBreak::class.java)

    override fun render(node: Node) {
        val html = context.writer
        offsets[node]?.let { html.raw(writer.lineBreak(it)) }
        html.tag("br", emptyMap(), true)
        html.line()
    }
}

/** commonmark renderer for raw HTML inside Markdown blocks: writes it through [HtmlSanitizer]. */
internal class SanitizedHtmlRenderer(
    private val context: HtmlNodeRendererContext,
    private val allowRemoteImages: Boolean,
) : NodeRenderer {

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(HtmlInline::class.java, HtmlBlock::class.java)

    override fun render(node: Node) {
        val html = context.writer
        when (node) {
            is HtmlInline -> html.raw(HtmlSanitizer.sanitize(node.literal, allowRemoteImages))
            is HtmlBlock -> {
                html.line()
                html.raw(HtmlSanitizer.sanitize(node.literal, allowRemoteImages))
                html.line()
            }
        }
    }
}
