package pl.mdreview.ide

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffContextEx
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.util.LineCol
import com.intellij.diff.util.Side
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.Alarm
import com.intellij.util.OpenSourceUtil
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import pl.mdreview.api.BlockInfo
import pl.mdreview.api.EditRegion
import pl.mdreview.api.LineRange
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.RenderedDiff
import pl.mdreview.api.TextEdit
import pl.mdreview.api.ViewMode
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * One rendered diff inside IntelliJ's diff window. Recomputes off the EDT whenever a side's document
 * changes (debounced), keeps change navigation (F7 / Shift+F7, see [ChangeNavigation]),
 * Ctrl/Cmd+click navigation to the source (FR-14), the per-block revert and apply buttons (FR-12) and editing a
 * block's source on a double-click (FR-13).
 */
class RenderedDiffViewer(
    private val context: DiffContext,
    private val request: ContentDiffRequest,
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

    /** The diff on screen, with the document stamps and texts it was computed from; block actions edit against it. */
    @Volatile
    private var rendered: Rendered? = null
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
        browserPanel.onBlockAction { action ->
            ApplicationManager.getApplication().invokeLater({
                if (!performBlockAction(action) && action.action.editSide != null) browserPanel.editRefused()
            }, ModalityState.any())
        }
        browserPanel.onSourceRequest(::blockSource)
        browserPanel.onBlockMenu(::showBlockMenu)
        for (action in navigationActions) {
            action.registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(action.shortcutActionId), root, disposable)
        }
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
        ReadAction.nonBlocking<Rendered?> {
            if (RenderedMarkdownDiffTool.isTooLarge(contents)) return@nonBlocking null
            val stamp = stamp()
            val texts = documents.map { it?.let { doc -> StringUtil.convertLineSeparators(doc.text) } }
            Rendered(RendererProvider.renderer.render(RenderRequest(texts[0], texts[1], mode, options, baseUris[0], baseUris[1])), stamp, texts)
        }
            .expireWith(disposable)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any(), ::show)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun show(result: Rendered?) {
        rendered = result
        if (result == null) {
            changes = emptyList()
            currentChange = -1
            root.showContent(largeFileNotice)
            statusLabel.text = MarkdownReviewBundle.message("status.too.large")
            return
        }
        val (diff, stamp) = result
        root.showContent(browserPanel.component)
        changes = diff.changes
        currentChange = currentChange.coerceAtMost(changes.size - 1)
        browserPanel.show(diff, blockActions(stamp))
        updateStatus()
    }

    /** `left:right` modification stamps of the documents, `-1` for a side without one. */
    private fun stamp(): String = documents.joinToString(":") { (it?.modificationStamp ?: -1).toString() }

    /**
     * The block buttons and source editing for a page rendered at [stamp]: only for sides that can be edited.
     * The merged view shows the right side, apart from removed blocks, so only the right side is edited there.
     */
    internal fun blockActions(stamp: String): BlockActions {
        val editLeft = mode == ViewMode.SIDE_BY_SIDE && canEdit(0)
        val editRight = canEdit(1)
        val buttons = settings.showBlockActions
        return BlockActions(
            revert = buttons && editRight,
            apply = buttons && editLeft,
            stamp = stamp,
            editLeft = editLeft,
            editRight = editRight,
        )
    }

    /** False for VCS revisions, read-only files and requests marked read-only. */
    private fun canEdit(side: Int): Boolean {
        val content = contents[side] as? DocumentContent ?: return false
        if (DiffUtil.isUserDataFlagSet(DiffUserDataKeys.FORCE_READ_ONLY, content, request, context)) return false
        return DiffUtil.canMakeWritable(content.document)
    }

    /**
     * Replaces the block's lines on the target side with the other side's lines, or for a source edit with the
     * edited Markdown, as one undoable command. Refused, with a re-render, when a document changed after the page was rendered. Returns whether it edited.
     */
    internal fun performBlockAction(request: BlockActionRequest): Boolean {
        val (diff, stamp) = rendered ?: return false
        if (request.stamp != stamp || stamp() != stamp) {
            scheduleRender(0)
            return false
        }
        if (!blockActions(stamp).allows(request.action)) return false
        val block = diff.blocks.getOrNull(request.idx) ?: return false
        request.action.editSide?.let { side -> return saveSource(block, side, request.source ?: return false) }
        val region = block.edit ?: return false
        // A moved block goes back as a whole: both ends' regions change in one command (FR-15).
        val regions = listOf(region) + listOfNotNull(block.movePartner?.let { diff.blocks.getOrNull(it)?.edit })
        val (target, source) = if (request.action == BlockAction.REVERT) 1 to 0 else 0 to 1
        val document = documents[target] ?: return false
        val sourceText = documents[source]?.charsSequence ?: ""
        fun edit(r: EditRegion): TextEdit {
            val (to, from) = if (target == 0) r.left to r.right else r.right to r.left
            return TextEdit.replaceLines(document.charsSequence, to, sourceText, from)
        }
        // Computed on the text before any of them applies, then applied from the end so earlier offsets stay valid.
        // Ends whose regions overlap cannot both apply: then only the clicked end changes, like any added or removed block.
        val both = regions.map(::edit).sortedByDescending { it.start }
        val overlap = both.zipWithNext().any { (later, earlier) -> earlier.end > later.start }
        val edits = if (overlap) listOf(edit(region)) else both
        val project = context.project
        if (!DiffUtil.makeWritable(project, document)) return false
        val name = MarkdownReviewBundle.message(if (request.action == BlockAction.REVERT) "command.revert.block" else "command.apply.block")
        WriteCommandAction.writeCommandAction(project).withName(name).run<RuntimeException> {
            // Global, so Undo works from the diff window, which has no editor for the document.
            if (project != null) CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
            for (edit in edits) document.replaceString(edit.start, edit.end, edit.replacement)
        }
        scheduleRender(0)
        return true
    }

    /**
     * The raw Markdown of block [idx] on [side] (`left` or `right`) as it was when the page at [stamp] was rendered,
     * for the editor a double-click opens (FR-13); `null` when that page is stale. Called on the CEF thread, so it
     * reads only the rendered snapshot; whether the side may be edited is the page's [BlockActions], and saving
     * checks it again.
     */
    internal fun blockSource(side: String, idx: Int, stamp: String): String? {
        val shown = rendered ?: return null
        if (shown.stamp != stamp) return null
        val target = if (side == "left") 0 else 1
        val block = shown.diff.blocks.getOrNull(idx) ?: return null
        val range = (if (target == 0) block.left else block.right) ?: return null
        val text = shown.texts[target] ?: return null
        return text.split('\n').subList(range.start, range.end).joinToString("\n")
    }

    /** Replaces the lines of [block] on [side] with [markdown], as one undoable command. */
    private fun saveSource(block: BlockInfo, side: Int, markdown: String): Boolean {
        val range = (if (side == 0) block.left else block.right) ?: return false
        val document = documents[side] ?: return false
        // Trailing line breaks would add blank lines after the block; an emptied editor removes the block's lines.
        val source = StringUtil.convertLineSeparators(markdown).trimEnd('\n')
        val sourceLines = if (source.isBlank()) LineRange(0, 0) else LineRange(0, source.count { it == '\n' } + 1)
        val edit = TextEdit.replaceLines(document.charsSequence, range, source, sourceLines)
        val project = context.project
        if (!DiffUtil.makeWritable(project, document)) return false
        WriteCommandAction.writeCommandAction(project).withName(MarkdownReviewBundle.message("command.edit.block")).run<RuntimeException> {
            if (project != null) CommandProcessor.getInstance().markCurrentCommandAsGlobal(project)
            document.replaceString(edit.start, edit.end, edit.replacement)
        }
        scheduleRender(0)
        return true
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

    /**
     * The right-click menu of block [idx] on [side] (P3.4), shown at [at] in the page's component, for the page
     * rendered at [stamp]; nothing for a stale page.
     */
    private fun showBlockMenu(side: String, idx: Int, stamp: String, at: Point) {
        val shown = rendered ?: return
        if (shown.stamp != stamp) return
        val block = shown.diff.blocks.getOrNull(idx) ?: return
        val target = if (side == "left") 0 else 1
        val range = (if (target == 0) block.left else block.right) ?: return
        val component = browserPanel.component
        JBPopupFactory.getInstance().createActionGroupPopup(
            null,
            DefaultActionGroup(blockMenuActions(target, range)),
            DataManager.getInstance().getDataContext(component),
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
        ).show(RelativePoint(component, at))
    }

    /** The actions of a block's menu: its source, the text diff at its line, and its history when it has one. */
    internal fun blockMenuActions(side: Int, range: LineRange): List<AnAction> = listOfNotNull(
        menuAction("block.menu.source") { navigateToSource(if (side == 0) "left" else "right", range.start) },
        if (context is DiffContextEx) menuAction("block.menu.text.diff") { openTextDiffAt(side, range.start) } else null,
        historyFile(side)?.let { file -> menuAction("block.menu.history") { showHistory(file, range) } },
    )

    private fun menuAction(key: String, perform: () -> Unit): AnAction =
        object : DumbAwareAction(MarkdownReviewBundle.message(key)) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    /**
     * Shows this request in the text viewer, scrolled to [line] of [side]. In a pull request window that viewer has
     * the review comment gutter, which is how a review comment starts from a rendered block. The rendered viewers
     * step aside for this one reopen only, so the viewer switcher offers them again right after.
     */
    internal fun openTextDiffAt(side: Int, line: Int) {
        val reopen = context as? DiffContextEx ?: return
        request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, com.intellij.openapi.util.Pair.create(if (side == 0) Side.LEFT else Side.RIGHT, line))
        context.putUserData(RenderedMarkdownDiffTool.TEXT_VIEWER_ONCE, true)
        try {
            reopen.reopenDiffRequest()
        } finally {
            ApplicationManager.getApplication().invokeLater({
                context.putUserData(RenderedMarkdownDiffTool.TEXT_VIEWER_ONCE, null)
                request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, null)
            }, ModalityState.any())
        }
    }

    /** The local file under version control that [side] shows, for its history; `null` for revisions and other files. */
    private fun historyFile(side: Int): VirtualFile? {
        val project = context.project ?: return null
        val file = (contents[side] as? DocumentContent)?.highlightFile?.takeIf { it.isInLocalFileSystem } ?: return null
        if (FileDocumentManager.getInstance().getDocument(file) !== documents[side]) return null
        return file.takeIf { ProjectLevelVcsManager.getInstance(project).getVcsFor(it) != null }
    }

    /**
     * Opens [file] with the block's lines selected and runs the platform's "Show History for Selection" on them, so
     * the history lists only the commits that changed this block.
     */
    private fun showHistory(file: VirtualFile, range: LineRange) {
        val project = context.project ?: return
        // navigateInEditor rather than FileEditorManager.openTextEditor, which 2026.3 deprecates with the other
        // synchronous openFile calls.
        if (!OpenFileDescriptor(project, file, range.start, 0).navigateInEditor(project, true)) return
        val editor = (FileEditorManager.getInstance(project).getSelectedEditor(file) as? TextEditor)?.editor ?: return
        val document = editor.document
        val last = (range.end - 1).coerceIn(range.start, document.lineCount - 1)
        editor.selectionModel.setSelection(document.getLineStartOffset(range.start), document.getLineEndOffset(last))
        val action = ActionManager.getInstance().getAction(SHOW_HISTORY_FOR_SELECTION) ?: return
        ActionManager.getInstance().tryToExecute(action, null, editor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
    }

    private fun navigateToSource(side: String, line: Int) {
        val content = contents[if (side == "left") 0 else 1] as? DocumentContent ?: return
        content.getNavigatable(LineCol(line, 0))?.let { OpenSourceUtil.navigate(true, it) }
    }

    /** Next and previous change, bound to the viewer's component under the shortcuts of the platform's actions. */
    internal val navigationActions = listOf(ChangeNavigation(forward = true), ChangeNavigation(forward = false))

    /**
     * Moves between the changes of the rendered page. The diff window's own Next/Previous Difference actions only
     * reach a viewer through internal API, so the viewer binds their shortcuts (F7 / Shift+F7 by default) to these
     * actions on its component, which take precedence while focus is inside it. At the first or last change the
     * action is disabled and the shortcut falls through to the diff window, which moves on to the next file.
     */
    internal inner class ChangeNavigation(private val forward: Boolean) : DumbAwareAction() {
        val shortcutActionId: String = if (forward) IdeActions.ACTION_NEXT_DIFF else IdeActions.ACTION_PREVIOUS_DIFF

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = if (forward) currentChange < changes.size - 1 else currentChange > 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            val target = currentChange + if (forward) 1 else -1
            if (target in changes.indices) goToChange(target)
        }
    }

    /** A diff on screen, the documents' stamps it was computed from and their texts, `\n`-separated, per side. */
    private data class Rendered(val diff: RenderedDiff, val stamp: String, val texts: List<String?>)

    private inner class RootPanel : JPanel(BorderLayout()) {
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
    }

    private companion object {
        /** The platform's "Show History for Selection" action. */
        const val SHOW_HISTORY_FOR_SELECTION = "Vcs.ShowHistoryForBlock"

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
