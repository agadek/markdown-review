package pl.mdreview.ide

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffContextEx
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.PrevNextDifferenceIterable
import com.intellij.diff.util.LineCol
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import pl.mdreview.api.BlockInfo
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.RenderedDiff
import pl.mdreview.api.ViewMode
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * One rendered diff inside IntelliJ's diff window. Recomputes off the EDT whenever a side's document
 * changes (debounced), keeps change navigation (F7 / Shift+F7 through [DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE])
 * and Ctrl/Cmd+click navigation to the source (FR-14).
 */
class RenderedDiffViewer(
    private val context: DiffContext,
    request: ContentDiffRequest,
    private val mode: ViewMode,
    viewFactory: (parent: Disposable, onSourceClick: (side: String, line: Int) -> Unit) -> RenderedDiffView = ::defaultView,
) : FrameDiffTool.DiffViewer {

    private val disposable = Disposer.newDisposable("Markdown Review viewer")
    private val contents: List<DiffContent> = request.contents
    private val documents = contents.map { (it as? DocumentContent)?.document }
    private val baseUris = contents.map(::baseUri)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val statusLabel = JBLabel(MarkdownReviewBundle.message("status.loading"))
    private val settings = MarkdownReviewSettings.getInstance()
    private val browserPanel = viewFactory(disposable) { side, line ->
        ApplicationManager.getApplication().invokeLater({ navigateToSource(side, line) }, ModalityState.any())
    }
    private val root = RootPanel()
    private val largeFileNotice by lazy { createLargeFileNotice() }

    private var changes: List<BlockInfo> = emptyList()
    private var currentChange = -1

    override fun getComponent(): JComponent = root

    override fun getPreferredFocusedComponent(): JComponent = browserPanel.component

    override fun init(): FrameDiffTool.ToolbarComponents {
        for (document in documents.filterNotNull()) {
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = scheduleRender(300)
            }, disposable)
        }
        ApplicationManager.getApplication().messageBus.connect(disposable)
            .subscribe(EditorColorsManager.TOPIC, EditorColorsListener { browserPanel.refreshTheme() })
        scheduleRender(0)
        return FrameDiffTool.ToolbarComponents().apply {
            popupActions = listOf<AnAction>(
                OptionToggle("toggle.ignore.whitespace", { settings.ignoreWhitespace }) {
                    settings.ignoreWhitespace = it
                },
                OptionToggle("toggle.collapse.unchanged", { settings.collapseUnchanged }) {
                    settings.collapseUnchanged = it
                },
            )
            statusPanel = statusLabel.apply { border = JBUI.Borders.empty(0, 8) }
        }
    }

    override fun dispose() = Disposer.dispose(disposable)

    private fun scheduleRender(delayMs: Int) {
        alarm.cancelAllRequests()
        alarm.addRequest(::render, delayMs)
    }

    private fun render() {
        val options = settings.options()
        ReadAction.nonBlocking<RenderedDiff?> {
            if (RenderedMarkdownDiffTool.isTooLarge(contents)) return@nonBlocking null
            val texts = documents.map { it?.let { doc -> StringUtil.convertLineSeparators(doc.text) } }
            RendererProvider.renderer.render(RenderRequest(texts[0], texts[1], mode, options, baseUris[0], baseUris[1]))
        }
            .expireWith(disposable)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any(), ::show)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun show(diff: RenderedDiff?) {
        if (diff == null) {
            changes = emptyList()
            currentChange = -1
            root.showContent(largeFileNotice)
            statusLabel.text = MarkdownReviewBundle.message("status.too.large")
            return
        }
        root.showContent(browserPanel.component)
        changes = diff.changes
        currentChange = currentChange.coerceAtMost(changes.size - 1)
        browserPanel.show(diff)
        updateStatus()
    }

    /** Shown instead of the page when a side is above [LargeFiles.limit] (NFR-4). */
    private fun createLargeFileNotice(): JComponent {
        val limit = LargeFiles.limit()
        val message = JBLabel(
            MarkdownReviewBundle.message("large.file.message", limit.maxLines, limit.maxBytes / (1024 * 1024)),
        )
        val panel = JPanel(VerticalLayout(JBUI.scale(8))).apply { border = JBUI.Borders.empty(16) }
        panel.add(message)
        if (context is DiffContextEx) {
            panel.add(JButton(MarkdownReviewBundle.message("large.file.open.text")).apply {
                addActionListener {
                    context.putUserData(RenderedMarkdownDiffTool.TEXT_VIEWER_FOR_LARGE_FILES, true)
                    context.reopenDiffRequest()
                }
            })
        }
        return panel
    }

    /** The directory of a local file as a `file:` URI ending with `/`, for resolving relative images. */
    private fun baseUri(content: DiffContent): String? {
        val dir = (content as? DocumentContent)?.highlightFile?.takeIf { it.isInLocalFileSystem }?.parent ?: return null
        return runCatching { dir.toNioPath().toUri().toString() }.getOrNull()?.let { if (it.endsWith("/")) it else "$it/" }
    }

    private fun updateStatus() {
        statusLabel.text = when {
            changes.isEmpty() -> MarkdownReviewBundle.message("status.no.differences")
            currentChange < 0 -> MarkdownReviewBundle.message("status.changes", changes.size)
            else -> MarkdownReviewBundle.message("status.change.of", currentChange + 1, changes.size)
        }
    }

    private fun goToChange(index: Int) {
        currentChange = index
        browserPanel.goTo(changes[index].idx)
        updateStatus()
    }

    private fun navigateToSource(side: String, line: Int) {
        val content = contents[if (side == "left") 0 else 1] as? DocumentContent ?: return
        content.getNavigatable(LineCol(line, 0))?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private val differenceIterable = object : PrevNextDifferenceIterable {
        override fun canGoNext() = currentChange < changes.size - 1
        override fun canGoPrev() = currentChange > 0
        override fun goNext() = goToChange(currentChange + 1)
        override fun goPrev() = goToChange(currentChange - 1)
    }

    private inner class RootPanel : JPanel(BorderLayout()), UiDataProvider {
        init {
            add(browserPanel.component, BorderLayout.CENTER)
        }

        fun showContent(content: JComponent) {
            if (content.parent === this) return
            removeAll()
            add(content, BorderLayout.CENTER)
            revalidate()
            repaint()
        }

        override fun uiDataSnapshot(sink: DataSink) {
            sink[DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE] = differenceIterable
        }
    }

    private companion object {
        fun defaultView(parent: Disposable, onSourceClick: (String, Int) -> Unit): RenderedDiffView =
            if (JBCefApp.isSupported()) MarkdownDiffPanel(parent, onSourceClick) else JcefUnavailableView()
    }

    private inner class OptionToggle(
        key: String,
        private val get: () -> Boolean,
        private val set: (Boolean) -> Unit,
    ) : DumbAwareToggleAction(MarkdownReviewBundle.message(key)) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = get()
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            set(state)
            scheduleRender(0)
        }
    }
}
