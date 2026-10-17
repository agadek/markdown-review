package pl.mdreview.engine

import org.commonmark.internal.util.Html5Entities

/**
 * Raw HTML from Markdown, split into sanitized markup and visible text.
 *
 * Sanitizing is an allowlist: only the tags and attributes GitHub keeps in rendered Markdown survive, URLs must use
 * a safe scheme, and the content of `<script>`, `<style>` and similar elements is dropped. Comments, doctypes and
 * processing instructions are dropped too. Text is decoded and, outside `<pre>`, its whitespace is collapsed, so
 * [visibleText] is what the browser shows and what the word diff compares.
 */
object HtmlFragment {

    sealed interface Part

    /** Sanitized markup, ready to write; [tag] is the lowercase element name, [closing] for an end tag. */
    data class Markup(val html: String, val tag: String, val closing: Boolean) : Part

    /** Visible text, decoded; the renderer escapes it. */
    data class TextRun(val text: String) : Part

    fun parse(html: String, allowRemoteImages: Boolean = false, openDetails: Boolean = false): List<Part> {
        val parts = mutableListOf<Part>()
        var pre = 0
        var pos = 0
        fun text(raw: String) {
            if (raw.isEmpty()) return
            val decoded = decode(raw)
            val visible = if (pre > 0) decoded else decoded.replace(whitespace, " ")
            val last = parts.lastOrNull()
            if (last is TextRun) parts[parts.size - 1] = TextRun(last.text + visible) else parts += TextRun(visible)
        }
        while (pos < html.length) {
            val m = token.find(html, pos)
            if (m == null) {
                text(html.substring(pos))
                break
            }
            text(html.substring(pos, m.range.first))
            pos = m.range.last + 1
            val name = m.groups[2]?.value?.lowercase() ?: continue // comment, doctype or processing instruction
            val closing = m.groups[1]!!.value == "/"
            if (name in dropWithContent) {
                if (!closing) {
                    val end = Regex("</$name\\s*>", RegexOption.IGNORE_CASE).find(html, pos)
                    pos = end?.range?.last?.plus(1) ?: html.length
                }
                continue
            }
            if (name !in allowedTags) continue
            if (name == "pre") pre = if (closing) maxOf(0, pre - 1) else pre + 1
            if (closing) {
                parts += Markup("</$name>", name, true)
                continue
            }
            val sb = StringBuilder("<").append(name)
            var hasOpen = false
            for (a in attribute.findAll(m.groups[3]?.value.orEmpty())) {
                val attr = a.groupValues[1].lowercase()
                if (attr !in allowedAttributes) continue
                val value = a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value
                if (attr == "open") hasOpen = true
                if (value == null) {
                    sb.append(' ').append(attr)
                    continue
                }
                val safe = when (attr) {
                    "href", "cite" -> value.takeIf { safeUrl(it, image = false) }
                    "src" -> when {
                        !safeUrl(value, image = true) -> null
                        !allowRemoteImages && isRemote(value) -> ""
                        else -> value
                    }
                    else -> value
                } ?: continue
                sb.append(' ').append(attr).append("=\"").append(safe.replace("\"", "&quot;").replace("<", "&lt;")).append('"')
            }
            if (name == "details" && openDetails && !hasOpen) sb.append(" open")
            sb.append('>')
            parts += Markup(sb.toString(), name, false)
        }
        return parts
    }

    fun visibleText(parts: List<Part>): String = parts.filterIsInstance<TextRun>().joinToString("") { it.text }

    fun visibleText(html: String): String = visibleText(parse(html))

    /** Text of every `<!-- comment -->` in [html], trimmed. */
    fun comments(html: String): List<String> = comment.findAll(html).map { it.groupValues[1].trim() }.toList()

    /** The comment text when [html] is exactly one comment, otherwise null. */
    fun singleComment(html: String): String? = wholeComment.matchEntire(html.trim())?.groupValues?.get(1)?.trim()

    /** How many `<details>` elements [html] opens minus how many it closes. */
    fun detailsBalance(html: String): Int {
        val text = comment.replace(html, "")
        return detailsOpen.findAll(text).count() - detailsClose.findAll(text).count()
    }

    private fun decode(text: String): String =
        if ('&' !in text) text else entity.replace(text) { Html5Entities.entityToString(it.value) }

    private fun isRemote(url: String): Boolean = normalizeUrl(url).let { it.startsWith("http:") || it.startsWith("https:") || it.startsWith("//") }

    /** Relative URLs, anchors and the http, https, mailto and file schemes; `data:image/...` for images. */
    private fun safeUrl(url: String, image: Boolean): Boolean {
        val u = normalizeUrl(url)
        val colon = u.indexOf(':')
        if (colon < 0 || u.indexOfAny(charArrayOf('/', '?', '#')).let { it in 0 until colon }) return true
        val scheme = u.substring(0, colon)
        if (scheme in safeSchemes) return true
        return image && u.startsWith("data:image/") && !u.startsWith("data:image/svg")
    }

    /** Decoded, lowercase, without the whitespace and control characters browsers ignore in URLs. */
    private fun normalizeUrl(url: String): String =
        decode(url).filterNot { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }.lowercase()

    private val token = Regex(
        "<!--.*?(?:-->|$)|<![^>]*>|<\\?.*?(?:\\?>|$)|<(/?)([A-Za-z][A-Za-z0-9-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val attribute = Regex("([^\\s\"'>/=]+)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+)))?")
    private val entity = Regex("&(?:#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[A-Za-z][A-Za-z0-9]{1,31});")
    private val whitespace = Regex("\\s+")
    private val comment = Regex("<!--(.*?)-->", RegexOption.DOT_MATCHES_ALL)
    private val wholeComment = Regex("<!--((?:(?!-->).)*)-->", RegexOption.DOT_MATCHES_ALL)
    private val detailsOpen = Regex("<details(?=[\\s>/])", RegexOption.IGNORE_CASE)
    private val detailsClose = Regex("</details\\s*>", RegexOption.IGNORE_CASE)

    private val safeSchemes = setOf("http", "https", "mailto", "file")

    private val dropWithContent = setOf(
        "script", "style", "iframe", "object", "embed", "template", "noscript", "noembed", "noframes",
        "textarea", "title", "xmp", "select", "frameset",
    )

    /** Roughly the elements GitHub keeps in rendered Markdown. */
    private val allowedTags = setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "span", "br", "hr", "b", "i", "strong", "em", "u", "s",
        "strike", "del", "ins", "mark", "small", "big", "a", "img", "picture", "kbd", "sub", "sup", "code", "pre",
        "tt", "samp", "var", "q", "cite", "abbr", "dfn", "blockquote", "ul", "ol", "li", "dl", "dt", "dd", "table",
        "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col", "details", "summary", "figure",
        "figcaption", "center", "ruby", "rt", "rp", "wbr",
    )

    private val allowedAttributes = setOf(
        "href", "src", "alt", "title", "width", "height", "align", "valign", "colspan", "rowspan", "open", "start",
        "type", "reversed", "lang", "dir", "datetime", "cite", "abbr", "span",
    )
}
