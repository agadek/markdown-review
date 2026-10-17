package pl.mdreview.ide

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * "Compare Markdown Files...": two selected files open straight away; with one or none selected the
 * user picks the rest in a file chooser (FR-9). Opens IntelliJ's own diff window, which starts with the
 * viewer chosen in the settings ([DefaultViewerOrder]), so the text viewer stays one click away.
 */
open class CompareMarkdownFilesAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
        e.presentation.isEnabledAndVisible = e.project != null &&
            selected.size <= 2 && selected.all { !it.isDirectory && MarkdownFiles.isMarkdown(it) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty().toMutableList()
        if (files.size < 2) files += chooseFiles(project, files.firstOrNull())
        if (files.size < 2) return
        showDiff(project, DiffRequestFactory.getInstance().createFromFiles(project, files[0], files[1]))
    }

    protected open fun showDiff(project: Project, request: DiffRequest) {
        DiffManager.getInstance().showDiff(project, request)
    }

    /** Asks for the missing files; [selected] is the one file already chosen, if any. */
    protected open fun chooseFiles(project: Project, selected: VirtualFile?): List<VirtualFile> {
        val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
            .withFileFilter { it.isDirectory || MarkdownFiles.isMarkdown(it) }
            .withTitle(MarkdownReviewBundle.message(if (selected == null) "compare.choose.title" else "compare.choose.second"))
            .withDescription(MarkdownReviewBundle.message("compare.choose.description"))
        return FileChooser.chooseFiles(descriptor, project, selected).toList()
    }
}
