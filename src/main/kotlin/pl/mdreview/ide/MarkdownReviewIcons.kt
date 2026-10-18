package pl.mdreview.ide

import com.intellij.openapi.util.IconLoader

/** Icons for the support links on the settings page. */
object MarkdownReviewIcons {
    @JvmField val SPONSORS = IconLoader.getIcon("/pl/mdreview/icons/sponsors.svg", MarkdownReviewIcons::class.java)
    @JvmField val KO_FI = IconLoader.getIcon("/pl/mdreview/icons/kofi.svg", MarkdownReviewIcons::class.java)

    const val SPONSORS_URL = "https://github.com/sponsors/agadek"
    const val KO_FI_URL = "https://ko-fi.com/gadek"
}
