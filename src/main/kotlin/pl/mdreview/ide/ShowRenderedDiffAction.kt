package pl.mdreview.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.ui.jcef.JBCefApp

/**
 * "Show Rendered Diff" (P3.4) in the Git log's changed files, in file history and in the Commit window: opens the
 * selected Markdown changes in a rendered viewer, whatever viewer those windows open with by default. The window is
 * IntelliJ's own diff window at [DefaultViewerOrder.RENDERED_DIFF_PLACE], so the viewer switcher, the text viewer and
 * the block menu work as in any other diff window.
 *
 * The requests are built here from the changes' revisions, through the diff and VCS APIs only: the platform's
 * `ShowDiffAction` and `ChangeDiffRequestProducer` live in `vcs-impl`, which plugins do not compile against.
 */
open class ShowRenderedDiffAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && JBCefApp.isSupported() && markdownChanges(e).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changes = markdownChanges(e)
        if (changes.isEmpty()) return
        DefaultViewerOrder.preferRendered()
        showDiff(project, changes)
    }

    protected open fun showDiff(project: Project, changes: List<Change>) {
        val chain = SimpleDiffRequestChain.fromProducers(changes.map { ChangeRequestProducer(project, it) })
        chain.putUserData(DiffUserDataKeys.PLACE, DefaultViewerOrder.RENDERED_DIFF_PLACE)
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
    }

    /**
     * One change as a diff request, loaded when the window gets to it (a revision may have to be read from Git). A
     * local file on the right is the file's own document, so block revert, apply and source editing work on it.
     */
    class ChangeRequestProducer(private val project: Project, val change: Change) : DiffRequestProducer {
        private val path = ChangesUtil.getFilePath(change)

        override fun getName(): String = path.presentableUrl

        override fun getContentType(): FileType = path.fileType

        override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
            indicator.checkCanceled()
            return SimpleDiffRequest(
                path.name,
                content(change.beforeRevision),
                content(change.afterRevision),
                title(change.beforeRevision),
                title(change.afterRevision),
            )
        }

        private fun content(revision: ContentRevision?): DiffContent {
            val factory = DiffContentFactory.getInstance()
            if (revision == null) return factory.createEmpty()
            if (revision is CurrentContentRevision) revision.virtualFile?.let { return factory.create(project, it) }
            val text = try {
                revision.content
            } catch (e: VcsException) {
                throw DiffRequestProducerException(e)
            }
            if (text == null) throw DiffRequestProducerException(MarkdownReviewBundle.message("rendered.diff.no.content", revision.file.name))
            return factory.create(project, text, revision.file)
        }

        private fun title(revision: ContentRevision?): String = when (revision) {
            null -> MarkdownReviewBundle.message("rendered.diff.title.none")
            is CurrentContentRevision -> MarkdownReviewBundle.message("rendered.diff.title.local")
            else -> MarkdownReviewBundle.message("rendered.diff.title.revision", shortRevision(revision.revisionNumber.asString()))
        }
    }

    companion object {
        /**
         * The selected changes of Markdown files, in selection order; F7 and the file list of the window walk them.
         * The Git log and file history give their selection as [VcsDataKeys.SELECTED_CHANGES]; the Commit window's
         * Changes list gives only [VcsDataKeys.CHANGES], which there holds the selected changes.
         */
        fun markdownChanges(e: AnActionEvent): List<Change> =
            markdownChanges((e.getData(VcsDataKeys.SELECTED_CHANGES) ?: e.getData(VcsDataKeys.CHANGES)).orEmpty().asList())

        /** Like the rendered viewers, a change counts when either side is Markdown, so a rename to `.txt` does too. */
        fun markdownChanges(changes: List<Change>): List<Change> =
            changes.filter { change ->
                listOfNotNull(ChangesUtil.getBeforePath(change), ChangesUtil.getAfterPath(change)).any(MarkdownFiles::isMarkdown)
            }

        /** A Git commit hash shortened the way the log shows it; other revision numbers stay as they are. */
        fun shortRevision(revision: String): String =
            if (revision.length == 40 && revision.all { it in '0'..'9' || it in 'a'..'f' }) revision.take(8) else revision
    }
}
