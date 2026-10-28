package pl.mdreview.ide

import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffContext
import com.intellij.ui.jcef.JBCefApp

/**
 * "Show Rendered Diff" (P3.4) in the Git log's changed files, in file history and in the Commit window: opens the
 * selected Markdown changes in a rendered viewer, whatever viewer those windows open with by default. The window is
 * IntelliJ's own diff window at [DefaultViewerOrder.RENDERED_DIFF_PLACE], so the viewer switcher, the text viewer and
 * the block menu work as in any other diff window.
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
        val context = ShowDiffContext()
        context.putChainContext(DiffUserDataKeys.PLACE, DefaultViewerOrder.RENDERED_DIFF_PLACE)
        ShowDiffAction.showDiffForChange(project, changes, 0, context)
    }

    companion object {
        /** The selected changes of Markdown files, in selection order; F7 and the file list of the window walk them. */
        fun markdownChanges(e: AnActionEvent): List<Change> =
            markdownChanges(e.getData(VcsDataKeys.SELECTED_CHANGES).orEmpty().asList())

        /** Like the rendered viewers, a change counts when either side is Markdown, so a rename to `.txt` does too. */
        fun markdownChanges(changes: List<Change>): List<Change> =
            changes.filter { change ->
                listOfNotNull(ChangesUtil.getBeforePath(change), ChangesUtil.getAfterPath(change)).any(MarkdownFiles::isMarkdown)
            }
    }
}
