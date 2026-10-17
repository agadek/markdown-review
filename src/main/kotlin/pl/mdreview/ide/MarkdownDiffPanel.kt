package pl.mdreview.ide

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import pl.mdreview.api.RenderedDiff
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.SwingConstants

/** What [RenderedDiffViewer] shows a [RenderedDiff] in. */
interface RenderedDiffView {
    val component: JComponent
    fun show(diff: RenderedDiff)
    fun refreshTheme()
    fun goTo(blockIdx: Int)
}

/**
 * JCEF view of one [RenderedDiff]. Knows nothing about diff requests: it shows a page, scrolls to
 * changes on request and reports clicks back through [onSourceClick] (called on the CEF thread).
 */
class MarkdownDiffPanel(parent: Disposable, private val onSourceClick: (side: String, line: Int) -> Unit) : RenderedDiffView {

    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

    /** Topmost visible block, kept so a re-render after an edit does not jump to the top. */
    @Volatile
    private var topIdx = 0
    private var current: RenderedDiff? = null

    override val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, browser)
        Disposer.register(parent, query)
        query.addHandler { msg ->
            val parts = msg.split('|', limit = 3)
            when (parts[0]) {
                "scroll" -> parts.getOrNull(1)?.toIntOrNull()?.let { topIdx = it }
                "link" -> parts.getOrNull(1)?.takeIf(::isExternal)?.let(BrowserUtil::browse)
                "source" -> {
                    val line = parts.getOrNull(2)?.toIntOrNull()
                    if (line != null) onSourceClick(parts[1], line)
                }
            }
            null
        }
    }

    override fun show(diff: RenderedDiff) {
        current = diff
        browser.loadHTML(HtmlPage.build(diff, query.inject("msg"), topIdx, MarkdownReviewSettings.getInstance().loadRemoteImages))
    }

    /** Rebuilds the page with fresh theme colors without recomputing the diff. */
    override fun refreshTheme() {
        current?.let(::show)
    }

    override fun goTo(blockIdx: Int) {
        browser.cefBrowser.executeJavaScript("mdr.goTo($blockIdx)", browser.cefBrowser.url, 0)
    }

    private fun isExternal(href: String) =
        href.startsWith("http://") || href.startsWith("https://") || href.startsWith("mailto:")
}

/** Shown instead of [MarkdownDiffPanel] when the IDE runtime has no JCEF (NFR-6). */
class JcefUnavailableView : RenderedDiffView {
    override val component: JComponent =
        JBLabel(MarkdownReviewBundle.message("jcef.unavailable"), SwingConstants.CENTER).apply { componentStyle = UIUtil.ComponentStyle.REGULAR }
    override fun show(diff: RenderedDiff) = Unit
    override fun refreshTheme() = Unit
    override fun goTo(blockIdx: Int) = Unit
}
