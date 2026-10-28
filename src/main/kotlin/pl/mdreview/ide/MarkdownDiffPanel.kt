package pl.mdreview.ide

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import pl.mdreview.api.RenderedDiff
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Point
import javax.swing.JComponent
import javax.swing.SwingConstants

/** What [RenderedDiffViewer] shows a [RenderedDiff] in. */
interface RenderedDiffView {
    val component: JComponent
    fun show(diff: RenderedDiff)

    /** Shows [diff] with the block buttons [actions] allows. */
    fun show(diff: RenderedDiff, actions: BlockActions) = show(diff)
    fun refreshTheme()
    fun goTo(blockIdx: Int)

    /** Sets what a click on a block button, or saving an edited block, calls (on the CEF thread). */
    fun onBlockAction(handler: (BlockActionRequest) -> Unit) = Unit

    /**
     * Sets where a double-click gets the source of block `idx` on `side` for the page rendered at `stamp`
     * (on the CEF thread); `null` keeps the page as it is (FR-13).
     */
    fun onSourceRequest(provider: (side: String, idx: Int, stamp: String) -> String?) = Unit

    /** Sets what a right-click on block `idx` on `side` calls, on the EDT, with the click's point in [component] (P3.4). */
    fun onBlockMenu(handler: (side: String, idx: Int, stamp: String, at: Point) -> Unit) = Unit

    /** An edited block was not saved, for example because the file changed meanwhile; the editor stays open. */
    fun editRefused() = Unit
}

/**
 * JCEF view of one [RenderedDiff]. Knows nothing about diff requests: it shows a page, scrolls to
 * changes on request and reports clicks back through [onSourceClick] (called on the CEF thread).
 *
 * While a block's source editor is open (FR-13), a new page is held back and shown when the editor closes, so a
 * re-render never takes what is being typed.
 */
class MarkdownDiffPanel(parent: Disposable, private val onSourceClick: (side: String, line: Int) -> Unit) : RenderedDiffView {

    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)

    /** Topmost visible block, kept so a re-render after an edit does not jump to the top. */
    @Volatile
    private var topIdx = 0
    private var current: RenderedDiff? = null
    private var currentActions = BlockActions.NONE
    private var blockActionHandler: (BlockActionRequest) -> Unit = {}
    private var sourceProvider: (String, Int, String) -> String? = { _, _, _ -> null }
    private var blockMenuHandler: (String, Int, String, Point) -> Unit = { _, _, _, _ -> }

    /** Whether the page has a block editor open; read and written on the EDT. */
    private var editing = false

    /** The page held back while [editing]. */
    private var pending: Pair<RenderedDiff, BlockActions>? = null

    override val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, browser)
        Disposer.register(parent, query)
        query.addHandler { msg ->
            val parts = msg.split('|', limit = 4)
            when (parts[0]) {
                "scroll" -> parts.getOrNull(1)?.toIntOrNull()?.let { topIdx = it }
                "link" -> parts.getOrNull(1)?.takeIf(::isExternalLink)?.let(BrowserUtil::browse)
                "edit" -> BlockActionRequest.parse(msg)?.let { request ->
                    // Saving closes the editor; the page that follows the edit replaces the one held back.
                    if (request.action.editSide != null) onEdt { editing = false; pending = null }
                    blockActionHandler(request)
                }
                "open" -> {
                    val idx = parts.getOrNull(2)?.toIntOrNull()
                    val stamp = parts.getOrNull(3)
                    val text = if (idx != null && stamp != null) sourceProvider(parts[1], idx, stamp) else null
                    if (idx != null && text != null) onEdt { openEditor(parts[1], idx, text) }
                }
                "menu" -> {
                    // menu|<side>|<idx>|<stamp>|<x>|<y>, the point in CSS pixels of the page.
                    val args = msg.split('|')
                    val idx = args.getOrNull(2)?.toIntOrNull()
                    val x = args.getOrNull(4)?.toDoubleOrNull()
                    val y = args.getOrNull(5)?.toDoubleOrNull()
                    if (args.size == 6 && idx != null && x != null && y != null) {
                        onEdt { blockMenuHandler(args[1], idx, args[3], Point(x.toInt(), y.toInt())) }
                    }
                }
                "closed" -> onEdt {
                    editing = false
                    pending?.let { (diff, actions) -> pending = null; show(diff, actions) }
                }
                "source" -> {
                    val line = parts.getOrNull(2)?.toIntOrNull()
                    if (line != null) onSourceClick(parts[1], line)
                }
            }
            null
        }
    }

    override fun show(diff: RenderedDiff) = show(diff, BlockActions.NONE)

    override fun show(diff: RenderedDiff, actions: BlockActions) {
        if (editing) {
            pending = diff to actions
            return
        }
        current = diff
        currentActions = actions
        val settings = MarkdownReviewSettings.getInstance()
        browser.loadHTML(HtmlPage.build(diff, query.inject("msg"), topIdx, settings.loadRemoteImages, actions, settings.renderMath))
    }

    /** Rebuilds the page with fresh theme colors without recomputing the diff. */
    override fun refreshTheme() {
        current?.let { show(it, currentActions) }
    }

    override fun onBlockAction(handler: (BlockActionRequest) -> Unit) {
        blockActionHandler = handler
    }

    override fun onSourceRequest(provider: (side: String, idx: Int, stamp: String) -> String?) {
        sourceProvider = provider
    }

    override fun onBlockMenu(handler: (side: String, idx: Int, stamp: String, at: Point) -> Unit) {
        blockMenuHandler = handler
    }

    override fun editRefused() {
        editing = true
        execute("mdr.editorRefused()")
    }

    /** Opens the editor of block [idx] on [side] with [text], unless the page was replaced meanwhile. */
    private fun openEditor(side: String, idx: Int, text: String) {
        editing = true
        execute("if (!mdr.openEditor(${HtmlPage.jsString(side)}, $idx, ${HtmlPage.jsString(text)})) mdrSend('closed')")
    }

    private fun execute(js: String) = browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeLater(action, ModalityState.any())

    override fun goTo(blockIdx: Int) = execute("mdr.goTo($blockIdx)")
}

/** Whether a sanitized link should be opened by the system browser. URI schemes are case-insensitive. */
internal fun isExternalLink(href: String): Boolean = when (href.substringBefore(':').lowercase()) {
    "http" -> href.startsWith("http://", ignoreCase = true)
    "https" -> href.startsWith("https://", ignoreCase = true)
    "mailto" -> href.startsWith("mailto:", ignoreCase = true)
    else -> false
}

/** Shown instead of [MarkdownDiffPanel] when the IDE runtime has no JCEF (NFR-6). */
class JcefUnavailableView : RenderedDiffView {
    override val component: JComponent =
        JBLabel(MarkdownReviewBundle.message("jcef.unavailable"), SwingConstants.CENTER).apply { componentStyle = UIUtil.ComponentStyle.REGULAR }
    override fun show(diff: RenderedDiff) = Unit
    override fun refreshTheme() = Unit
    override fun goTo(blockIdx: Int) = Unit
}
