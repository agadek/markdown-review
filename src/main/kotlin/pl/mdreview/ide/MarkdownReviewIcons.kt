package pl.mdreview.ide

import com.intellij.openapi.util.IconLoader

/** Icons for the rendered viewers in the diff window's viewer switcher and for the support links on the settings page. */
object MarkdownReviewIcons {
    @JvmField val RENDERED_SIDE_BY_SIDE = IconLoader.getIcon("/pl/mdreview/icons/renderedSideBySide.svg", MarkdownReviewIcons::class.java)
    @JvmField val RENDERED_MERGED = IconLoader.getIcon("/pl/mdreview/icons/renderedMerged.svg", MarkdownReviewIcons::class.java)
    @JvmField val SPONSORS = IconLoader.getIcon("/pl/mdreview/icons/sponsors.svg", MarkdownReviewIcons::class.java)
    @JvmField val KO_FI = IconLoader.getIcon("/pl/mdreview/icons/kofi.svg", MarkdownReviewIcons::class.java)

    const val SPONSORS_URL = "https://github.com/sponsors/agadek"
    const val KO_FI_URL = "https://ko-fi.com/gadek"
}
