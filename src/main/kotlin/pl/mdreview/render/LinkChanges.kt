package pl.mdreview.render

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.renderer.html.AttributeProvider
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockExtractor
import java.net.URI
import java.net.URISyntaxException

/**
 * Links and images whose visible text is equal on both sides of a modified block but whose target
 * changed. The word diff cannot show such a change, so the renderer marks the element itself.
 */
internal object LinkChanges {

    /** Changed elements of each side, mapped to the other side's URL. */
    class Result(val left: Map<Node, String>, val right: Map<Node, String>) {
        companion object {
            val NONE = Result(emptyMap(), emptyMap())
        }
    }

    private class Target(val node: Node, val label: String, val url: String)

    /**
     * Pairs the links of [left] and [right] by their text (image: alt text) and occurrence: the second
     * link labelled "docs" on the left pairs with the second one labelled "docs" on the right.
     */
    fun between(left: Block, right: Block): Result {
        val leftTargets = targets(left.renderNodes)
        if (leftTargets.isEmpty()) return Result.NONE
        val rightByLabel = targets(right.renderNodes).groupBy { it.label }
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

    private fun targets(nodes: List<Node>): List<Target> {
        val out = mutableListOf<Target>()
        val visitor = object : AbstractVisitor() {
            override fun visit(link: Link) {
                out += Target(link, "a:" + BlockExtractor.plainText(link), link.destination)
                visitChildren(link)
            }

            override fun visit(image: Image) {
                val alt = generateSequence(image.firstChild) { it.next }.joinToString("") { BlockExtractor.plainText(it) }
                out += Target(image, "img:$alt", image.destination)
            }
        }
        nodes.forEach { it.accept(visitor) }
        return out
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
