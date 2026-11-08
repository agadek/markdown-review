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

    /** Value of attribute [name] in [markup], decoded; `null` when it has none. */
    fun attribute(markup: Markup, name: String): String? {
        for (match in attribute.findAll(markup.html)) {
            if (!match.groupValues[1].equals(name, ignoreCase = true)) continue
            val raw = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value
            return raw?.let(::decode).orEmpty()
        }
        return null
    }

    /** [markup] with attribute [name] set to [value], replacing an existing value or adding the attribute. */
    fun withAttribute(markup: Markup, name: String, value: String): Markup {
        if (markup.closing) return markup
        val encoded = value.replace("\"", "&quot;").replace("<", "&lt;")
        val existing = attribute.findAll(markup.html).firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }
        val html = if (existing != null) {
            markup.html.substring(0, existing.range.first) + name + "=\"" + encoded + "\"" +
                markup.html.substring(existing.range.last + 1)
        } else {
            markup.html.dropLast(1) + " " + name + "=\"" + encoded + "\">"
        }
        return Markup(html, markup.tag, markup.closing)
    }

    /**
     * [parts] as [parse] would return them with `allowRemoteImages = false`: the `src` of a remote image becomes
     * empty. [parse] kept remote sources for link and image change detection; the renderer applies this when it
     * writes the page.
     */
    fun withoutRemoteImages(parts: List<Part>): List<Part> = parts.map { part ->
        if (part !is Markup || part.closing) return@map part
        val match = attribute.findAll(part.html).firstOrNull {
            it.groupValues[1].equals("src", ignoreCase = true) &&
                (it.groups[2]?.value ?: it.groups[3]?.value ?: it.groups[4]?.value)?.let(::isRemote) == true
        } ?: return@map part
        val value = match.groups[2]?.range ?: match.groups[3]?.range ?: match.groups[4]?.range ?: return@map part
        val html = part.html.substring(0, value.first) + part.html.substring(value.last + 1)
        Markup(html, part.tag, false)
    }

    /** [parts] as [parse] would return them with `openDetails = true`: every `<details>` without `open` gets it. */
    fun withOpenDetails(parts: List<Part>): List<Part> = parts.map { part ->
        if (part is Markup && !part.closing && part.tag == "details" && attribute(part, "open") == null) {
            Markup(part.html.dropLast(1) + " open>", part.tag, false)
        } else {
            part
        }
    }

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
