package pl.mdreview.render

/**
 * Strips the dangerous parts of raw HTML from Markdown. The page's Content-Security-Policy already
 * blocks scripts and event handlers; this keeps the markup itself clean as a second line.
 */
object HtmlSanitizer {

    private val dropWithContent = Regex(
        "<(script|style|iframe|object|embed|frame|frameset|template|noscript)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val dropTag = Regex(
        "</?(script|style|iframe|object|embed|frame|frameset|template|noscript|base|meta|link|form)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val eventAttribute = Regex("\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", RegexOption.IGNORE_CASE)
    private val scriptUrl = Regex(
        "(href|src|action|formaction|xlink:href)\\s*=\\s*([\"']?)\\s*(javascript|vbscript|data:text/html)[^\"'>\\s]*\\2",
        RegexOption.IGNORE_CASE,
    )
    private val remoteSrc = Regex("(src)\\s*=\\s*([\"'])\\s*https?://[^\"']*\\2", RegexOption.IGNORE_CASE)

    fun sanitize(html: String, allowRemoteImages: Boolean = false): String {
        var out = dropWithContent.replace(html, "")
        out = dropTag.replace(out, "")
        out = eventAttribute.replace(out, "")
        out = scriptUrl.replace(out) { "${it.groupValues[1]}=\"#\"" }
        if (!allowRemoteImages) out = remoteSrc.replace(out) { "${it.groupValues[1]}=\"\"" }
        return out
    }
}
