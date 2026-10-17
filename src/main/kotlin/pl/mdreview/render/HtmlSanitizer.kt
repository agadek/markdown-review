package pl.mdreview.render

import pl.mdreview.engine.HtmlFragment

/**
 * Keeps only safe HTML from Markdown (see [HtmlFragment]). The page's Content-Security-Policy already blocks
 * scripts and event handlers; this keeps the markup itself clean as a second line (NFR-8).
 */
object HtmlSanitizer {

    fun sanitize(html: String, allowRemoteImages: Boolean = false): String =
        write(HtmlFragment.parse(html, allowRemoteImages))

    fun write(parts: List<HtmlFragment.Part>, text: (String) -> String = DiffHtmlRenderer::escape): String =
        parts.joinToString("") {
            when (it) {
                is HtmlFragment.Markup -> it.html
                is HtmlFragment.TextRun -> text(it.text)
            }
        }
}
