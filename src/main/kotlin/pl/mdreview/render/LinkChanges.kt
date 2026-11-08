package pl.mdreview.render

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.renderer.html.AttributeProvider
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.HtmlFragment
import pl.mdreview.engine.MarkdownBlockSource
import java.net.URI
import java.net.URISyntaxException

/**
 * Links and images whose visible text is equal on both sides of a modified block but whose target
 * changed. The word diff cannot show such a change, so the renderer marks the element itself.
 *
 * Commonmark links pair by [Node]; links in [BlockContent.Html] parts pair by the occurrence of their
 * `<a href>` / `<img src>` element in the part list, which is what the renderer counts while writing them.
 */
internal object LinkChanges {

    /**
     * Changed elements of each side, mapped to the other side's URL. [left] / [right] are commonmark nodes;
     * [leftHtml] / [rightHtml] map the occurrence index of an `<a>` or `<img>` in that side's parts to the other
     * side's URL.
     */
    class Result(
        val left: Map<Node, String>,
        val right: Map<Node, String>,
        val leftHtml: Map<Int, String> = emptyMap(),
        val rightHtml: Map<Int, String> = emptyMap(),
    ) {
        fun nodeLinks(side: String): Map<Node, String> = if (side == "left") left else right

        fun htmlLinks(side: String): Map<Int, String> = if (side == "left") leftHtml else rightHtml

        companion object {
            val NONE = Result(emptyMap(), emptyMap())
        }
    }

    private val MODEL_TARGET_KINDS = setOf(BlockKind.IMAGE, BlockKind.INCLUDE)

    private class Target(val node: Node, val label: String, val url: String)

    private class HtmlTarget(val occurrence: Int, val label: String, val url: String)

    /**
     * Pairs the links of [left] and [right] by their text (image: alt text) and occurrence: the second
     * link labelled "docs" on the left pairs with the second one labelled "docs" on the right.
     */
    fun between(left: Block, right: Block): Result {
        // A block image's or include's target is a model field, not an element in its inline parts.
        if (left.kind == right.kind && left.kind in MODEL_TARGET_KINDS &&
            left.target != null && right.target != null
        ) {
            if (left.target == right.target) return Result.NONE
            return Result(emptyMap(), emptyMap(), mapOf(0 to right.target), mapOf(0 to left.target))
        }
        val leftContent = left.content
        val rightContent = right.content
        if (leftContent is BlockContent.Html && rightContent is BlockContent.Html) {
            return betweenHtml(leftContent.parts, rightContent.parts)
        }
        return betweenCommonmark(left.nodes, right.nodes)
    }

    private fun betweenCommonmark(leftNodes: List<Node>, rightNodes: List<Node>): Result {
        val leftTargets = targets(leftNodes)
        if (leftTargets.isEmpty()) return Result.NONE
        val rightByLabel = targets(rightNodes).groupBy { it.label }
        val leftByLabel = leftTargets.groupBy { it.label }
        val leftChanged = java.util.IdentityHashMap<Node, String>()
        val rightChanged = java.util.IdentityHashMap<Node, String>()
        for ((label, lefts) in leftByLabel) {
            val rights = rightByLabel[label] ?: continue
            for (k in 0 until minOf(lefts.size, rights.size)) {
                val l = lefts[k]
                val r = rights[k]
                if (l.url != r.url) {
                    leftChanged[l.node] = r.url
                    rightChanged[r.node] = l.url
                }
            }
        }
        return if (leftChanged.isEmpty()) Result.NONE else Result(leftChanged, rightChanged)
    }

    private fun betweenHtml(leftParts: List<HtmlFragment.Part>, rightParts: List<HtmlFragment.Part>): Result {
        val leftTargets = htmlTargets(leftParts)
        if (leftTargets.isEmpty()) return Result.NONE
        val rightByLabel = htmlTargets(rightParts).groupBy { it.label }
        val leftByLabel = leftTargets.groupBy { it.label }
        val leftChanged = mutableMapOf<Int, String>()
        val rightChanged = mutableMapOf<Int, String>()
        for ((label, lefts) in leftByLabel) {
            val rights = rightByLabel[label] ?: continue
            for (k in 0 until minOf(lefts.size, rights.size)) {
                val l = lefts[k]
                val r = rights[k]
                if (l.url != r.url) {
                    leftChanged[l.occurrence] = r.url
                    rightChanged[r.occurrence] = l.url
                }
            }
        }
        return if (leftChanged.isEmpty()) Result.NONE else Result(emptyMap(), emptyMap(), leftChanged, rightChanged)
    }

    private fun targets(nodes: List<Node>): List<Target> {
        val out = mutableListOf<Target>()
        val visitor = object : AbstractVisitor() {
            override fun visit(link: Link) {
                out += Target(link, "a:" + MarkdownBlockSource.plainText(link), link.destination)
                visitChildren(link)
            }

            override fun visit(image: Image) {
                val alt = generateSequence(image.firstChild) { it.next }.joinToString("") { MarkdownBlockSource.plainText(it) }
                out += Target(image, "img:$alt", image.destination)
            }
        }
        nodes.forEach { it.accept(visitor) }
        return out
    }

    /** `<a href>` and `<img src>` elements in document order, with their text (alt text for images). */
    private fun htmlTargets(parts: List<HtmlFragment.Part>): List<HtmlTarget> {
        val out = mutableListOf<HtmlTarget>()
        var occurrence = 0
        for (i in parts.indices) {
            val part = parts[i] as? HtmlFragment.Markup ?: continue
            if (part.closing || (part.tag != "a" && part.tag != "img")) continue
            val index = occurrence++
            if (part.tag == "img") {
                val src = HtmlFragment.attribute(part, "src") ?: continue
                val alt = HtmlFragment.attribute(part, "alt").orEmpty()
                out += HtmlTarget(index, "img:$alt", src)
            } else {
                val href = HtmlFragment.attribute(part, "href") ?: continue
                out += HtmlTarget(index, "a:" + elementText(parts, i + 1), href)
            }
        }
        return out
    }

    /** Text runs until the `</a>` matching the opening tag before [from]. */
    private fun elementText(parts: List<HtmlFragment.Part>, from: Int): String {
        val text = StringBuilder()
        var depth = 1
        for (i in from until parts.size) {
            when (val part = parts[i]) {
                is HtmlFragment.TextRun -> text.append(part.text)
                is HtmlFragment.Markup -> if (part.tag == "a") depth += if (part.closing) -1 else 1
            }
            if (depth == 0) break
        }
        return text.toString()
    }
}

/**
 * Adds to the HTML commonmark writes: `mdr-link-changed` / `mdr-img-changed` with a tooltip on the
 * links in [linkNotes] (node to tooltip text), and image sources resolved against [baseUri].
 */
internal class LinkAttributeProvider(
    private val baseUri: String?,
    private val linkNotes: Map<Node, String>,
) : AttributeProvider {

    override fun setAttributes(node: Node, tagName: String, attributes: MutableMap<String, String>) {
        if (node is Image && baseUri != null) {
            attributes["src"]?.let { src -> resolve(baseUri, src)?.let { attributes["src"] = it } }
        }
        val note = linkNotes[node] ?: return
        val cls = if (node is Image) "mdr-img-changed" else "mdr-link-changed"
        attributes["class"] = attributes["class"]?.let { "$it $cls" } ?: cls
        attributes["title"] = attributes["title"]?.let { "$it\n$note" } ?: note
    }

    companion object {
        private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val fileWithoutAuthority = Regex("^file:/(?!/)")

        /**
         * [src] resolved against [base] when it is a relative path (`img.png`, `../docs/a.svg`);
         * `null` for absolute URLs, root-relative paths, fragments and anything that does not parse.
         */
        fun resolve(base: String, src: String): String? {
            if (src.isEmpty() || scheme.containsMatchIn(src) || src.startsWith("/") || src.startsWith("#")) return null
            return try {
                // java.net.URI drops the empty authority of `file:///`; browsers expect it back.
                URI(base).resolve(URI(src)).toString().replace(fileWithoutAuthority, "file:///")
            } catch (_: URISyntaxException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
