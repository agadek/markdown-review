package pl.mdreview.ide

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefApp

/**
 * "Compare Markdown Files...": two selected files open straight away; with one or none selected the
 * user picks the rest in a file chooser (FR-9). Opens IntelliJ's own diff window with the rendered
 * side-by-side viewer preselected, so the text viewer stays one click away.
 */
class CompareMarkdownFilesAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty()
        e.presentation.isEnabledAndVisible = e.project != null &&
            selected.size <= 2 && selected.all { !it.isDirectory && isMarkdown(it) }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).orEmpty().toMutableList()
        if (files.size < 2) {
            val descriptor = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor()
                .withFileFilter(::isMarkdown)
                .withTitle(MarkdownReviewBundle.message(if (files.isEmpty()) "compare.choose.title" else "compare.choose.second"))
                .withDescription(MarkdownReviewBundle.message("compare.choose.description"))
            files += FileChooser.chooseFiles(descriptor, project, files.firstOrNull())
        }
        if (files.size < 2) return
        val request = DiffRequestFactory.getInstance().createFromFiles(project, files[0], files[1])
        DiffManagerEx.getInstance().diffTools.firstOrNull { it is RenderedSideBySideTool }
            ?.takeIf { JBCefApp.isSupported() }
            ?.let { request.putUserData(DiffUserDataKeysEx.FORCE_DIFF_TOOL, it) }
        DiffManager.getInstance().showDiff(project, request)
    }

    private fun isMarkdown(file: VirtualFile) = file.isDirectory || file.extension?.lowercase() in RenderedMarkdownDiffTool.EXTENSIONS
}
