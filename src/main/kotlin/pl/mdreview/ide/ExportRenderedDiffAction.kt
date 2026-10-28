package pl.mdreview.ide

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import org.cef.callback.CefPdfPrintCallback
import org.cef.misc.CefPdfPrintSettings
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.RenderedDiff
import pl.mdreview.api.ViewMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Export Rendered Diff | HTML... / PDF..." (P3.6) next to Show Rendered Diff, in the Git log's changed files, in file
 * history and in the Commit window: renders every selected Markdown change into one document, with a table of contents
 * and one section per file, and saves it as a standalone HTML file ([Html]) or a PDF ([Pdf]).
 *
 * The page is drawn in a hidden browser first, so Mermaid diagrams and formulas come out drawn, as in the single-file
 * export from the viewer: the HTML is a snapshot of that page without scripts ([HtmlExport]), the PDF is that page
 * printed. The view is the one the diff window opens Markdown with (merged when that is the merged viewer, side by
 * side otherwise) and follows the viewer's settings.
 */
open class ExportRenderedDiffAction(private val pdf: Boolean = false) : DumbAwareAction() {

    class Html : ExportRenderedDiffAction(pdf = false)

    class Pdf : ExportRenderedDiffAction(pdf = true)

    /** The "Export Rendered Diff" submenu, shown when the selection has a Markdown change. */
    class Group : DefaultActionGroup(), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = available(e)
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = available(e)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changes = ShowRenderedDiffAction.markdownChanges(e)
        if (changes.isEmpty()) return
        val ext = extension
        val descriptor = FileSaverDescriptor(
            MarkdownReviewBundle.message("export.changes.$ext.title"),
            MarkdownReviewBundle.message("export.changes.$ext.description"),
        )
        // The constructor that takes extensions is deprecated.
        descriptor.withExtensionFilter(ext)
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as Path?, defaultFileName(pdf))?.file?.toPath() ?: return
        export(project, changes, target)
    }

    private val extension get() = if (pdf) "pdf" else "html"

    protected open fun export(project: Project, changes: List<Change>, target: Path) {
        val settings = MarkdownReviewSettings.getInstance()
        val mode = if (settings.defaultViewer == DefaultViewer.RENDERED_MERGED) ViewMode.MERGED else ViewMode.SIDE_BY_SIDE
        val title = MarkdownReviewBundle.message("export.changes.document.title", changes.size)
        object : Task.Backgroundable(project, MarkdownReviewBundle.message("export.changes.progress"), true) {
            private var page: RenderedDiff? = null

            override fun run(indicator: ProgressIndicator) {
                val files = changes.mapIndexed { i, change ->
                    indicator.checkCanceled()
                    indicator.fraction = i.toDouble() / changes.size
                    render(project, change, mode, settings)
                }
                page = HtmlExport.combine(title, files, MarkdownReviewBundle.message("status.no.differences"))
            }

            override fun onSuccess() {
                val combined = page ?: return
                HiddenPage(combined, title, target, pdf, settings) { ok -> notifyExported(project, target, ok) }.start()
            }

            override fun onThrowable(error: Throwable) {
                super.onThrowable(error)
                notifyExported(project, target, false)
            }
        }.queue()
    }

    /** One change rendered, or the reason it could not be. */
    internal fun render(project: Project, change: Change, mode: ViewMode, settings: MarkdownReviewSettings): HtmlExport.ExportedFile {
        val path = ChangesUtil.getFilePath(change)
        val shown = project.basePath?.let { FileUtil.getRelativePath(it, path.path, '/') }?.takeIf { !it.startsWith("..") } ?: path.path
        val label = changeLabel(change)
        return try {
            val left = text(change.beforeRevision)
            val right = text(change.afterRevision)
            val base = path.ioFile.parentFile?.takeIf { it.isDirectory }?.toURI()?.toString()
            val diff = RendererProvider.renderer.render(RenderRequest(left, right, mode, settings.options(), base, base))
            HtmlExport.ExportedFile(shown, label, diff)
        } catch (e: VcsException) {
            HtmlExport.ExportedFile(shown, label, null, MarkdownReviewBundle.message("rendered.diff.no.content", path.name))
        }
    }

    private fun changeLabel(change: Change): String = when (change.type) {
        Change.Type.NEW -> MarkdownReviewBundle.message("export.change.added")
        Change.Type.DELETED -> MarkdownReviewBundle.message("export.change.deleted")
        Change.Type.MOVED -> MarkdownReviewBundle.message(
            "export.change.moved",
            ChangesUtil.getBeforePath(change)?.name.orEmpty(),
        )
        else -> MarkdownReviewBundle.message("export.change.modified")
    }

    /** A side's text with `\n` line separators; `null` when that side does not exist. */
    private fun text(revision: ContentRevision?): String? {
        if (revision == null) return null
        // A local file's revision reads its document, unsaved edits included, under its own read action.
        val text = revision.content ?: throw VcsException(MarkdownReviewBundle.message("rendered.diff.no.content", revision.file.name))
        return StringUtil.convertLineSeparators(text)
    }

    private fun notifyExported(project: Project, target: Path, ok: Boolean) {
        if (ok) LocalFileSystem.getInstance().refreshNioFiles(listOf(target))
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                MarkdownReviewBundle.message(if (ok) "export.done" else "export.failed", target.toString()),
                if (ok) NotificationType.INFORMATION else NotificationType.ERROR,
            )
        if (ok) {
            notification.addAction(NotificationAction.createSimpleExpiring(MarkdownReviewBundle.message("export.open")) {
                BrowserUtil.browse(target.toUri().toString())
            })
        }
        notification.notify(project)
    }

    /**
     * Loads [page] in an off-screen browser, waits until its diagrams and formulas are drawn, then writes the HTML
     * snapshot or prints the PDF to [target] and disposes the browser. Gives up after [TIMEOUT_SECONDS].
     */
    private class HiddenPage(
        private val page: RenderedDiff,
        private val title: String,
        private val target: Path,
        private val pdf: Boolean,
        private val settings: MarkdownReviewSettings,
        private val done: (Boolean) -> Unit,
    ) {
        private val disposable: Disposable = Disposer.newDisposable("Markdown Review export")
        private val finished = AtomicBoolean(false)
        private val drawn = AtomicBoolean(false)
        private var poll: ScheduledFuture<*>? = null
        private var timeout: ScheduledFuture<*>? = null

        fun start() {
            val browser = JBCefBrowser.createBuilder().setOffScreenRendering(true).setCreateImmediately(true).build()
            Disposer.register(disposable, browser)
            // Room to lay the page out as in a diff window; the component is never shown.
            browser.component.setSize(PAGE_WIDTH, PAGE_HEIGHT)
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            Disposer.register(disposable, query)
            query.addHandler { msg ->
                when {
                    msg == "drawn" && drawn.compareAndSet(false, true) -> {
                        poll?.cancel(false)
                        if (pdf) {
                            browser.cefBrowser.printToPDF(target.toString(), CefPdfPrintSettings(), CefPdfPrintCallback { _, ok -> finish(ok) })
                        } else {
                            browser.cefBrowser.executeJavaScript("mdr.snapshot()", browser.cefBrowser.url, 0)
                        }
                    }
                    msg.startsWith("export|") -> {
                        val snapshot = msg.substring("export|".length)
                        AppExecutorUtil.getAppExecutorService().execute {
                            finish(runCatching { Files.writeString(target, HtmlExport.finish(snapshot, title, settings.loadRemoteImages)) }.isSuccess)
                        }
                    }
                }
                null
            }
            browser.loadHTML(
                HtmlPage.build(
                    page, query.inject("msg"), 0, settings.loadRemoteImages, BlockActions.NONE, settings.renderMath,
                    UserStylesheet.load(settings.customCssPath),
                ),
            )
            val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
            poll = scheduler.scheduleWithFixedDelay({
                if (!drawn.get()) browser.cefBrowser.executeJavaScript(WAIT_JS, browser.cefBrowser.url, 0)
            }, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS)
            timeout = scheduler.schedule({ finish(false) }, TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        private fun finish(ok: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            poll?.cancel(false)
            timeout?.cancel(false)
            ApplicationManager.getApplication().invokeLater({ Disposer.dispose(disposable) }, ModalityState.any())
            done(ok)
        }
    }

    companion object {
        /** `markdown-changes.diff.html`, or `.pdf` for a PDF. */
        fun defaultFileName(pdf: Boolean) = "markdown-changes.diff." + if (pdf) "pdf" else "html"

        fun available(e: AnActionEvent) =
            e.project != null && JBCefApp.isSupported() && ShowRenderedDiffAction.markdownChanges(e).isNotEmpty()

        /** Registered in plugin.xml; the viewer's export notifies through it too. */
        const val NOTIFICATION_GROUP = "Markdown Review"
        private const val PAGE_WIDTH = 1200
        private const val PAGE_HEIGHT = 900
        private const val POLL_MS = 300L
        private const val TIMEOUT_SECONDS = 120L

        /**
         * Sends `drawn` once the page has loaded and every diagram and formula is drawn or has failed. Formulas count
         * only when KaTeX is on the page: with math rendering off they stay source and are never drawn.
         */
        private const val WAIT_JS = """
            (function() {
              if (!window.mdrSend || !window.mdr || document.readyState !== 'complete') return;
              var waiting = window.mermaid ? document.querySelectorAll('.mdr-mermaid:not(.mdr-mermaid-drawn):not(.mdr-mermaid-failed)').length : 0;
              if (window.katex) waiting += document.querySelectorAll('.mdr-math:not(.mdr-math-drawn):not(.mdr-math-failed)').length;
              if (waiting === 0) mdrSend('drawn');
            })();
        """
    }
}
