package pl.mdreview.ide

import com.intellij.diff.DiffManagerEx
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.impl.DiffSettingsHolder.DiffSettings
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.jcef.JBCefApp
import com.intellij.vcsUtil.VcsUtil

class ShowRenderedDiffActionTest : BasePlatformTestCase() {

    private class TestAction : ShowRenderedDiffAction() {
        val shown = mutableListOf<List<Change>>()

        override fun showDiff(project: Project, changes: List<Change>) {
            shown += changes
        }
    }

    private class Revision(private val path: FilePath, private val text: String) : ContentRevision {
        override fun getContent() = text
        override fun getFile() = path
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    private lateinit var savedOrder: List<String>
    private lateinit var savedViewer: Pair<DefaultViewer, DefaultViewer?>

    override fun setUp() {
        super.setUp()
        savedOrder = DiffSettings.getSettings(DefaultViewerOrder.RENDERED_DIFF_PLACE).diffToolsOrder
        val settings = MarkdownReviewSettings.getInstance()
        savedViewer = settings.defaultViewer to settings.appliedDefaultViewer
    }

    override fun tearDown() {
        try {
            DiffSettings.getSettings(DefaultViewerOrder.RENDERED_DIFF_PLACE).diffToolsOrder = savedOrder
            val settings = MarkdownReviewSettings.getInstance()
            settings.defaultViewer = savedViewer.first
            settings.appliedDefaultViewer = savedViewer.second
        } finally {
            super.tearDown()
        }
    }

    private fun path(name: String) = VcsUtil.getFilePath("/repo/$name", false)

    /** A change as the Git log shows it: [before] renamed or edited into [after]; `null` for an added or deleted file. */
    private fun change(before: String?, after: String?) =
        Change(before?.let { Revision(path(it), "# old\n") }, after?.let { Revision(path(it), "# new\n") })

    private fun event(action: ShowRenderedDiffAction, vararg selected: Change) = TestActionEvent.createTestEvent(
        action,
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(VcsDataKeys.SELECTED_CHANGES, arrayOf(*selected))
            .build(),
    )

    fun `test only Markdown changes are shown, in selection order`() {
        val readme = change("README.md", "README.md")
        val code = change("Main.kt", "Main.kt")
        val added = change(null, "docs/guide.markdown")
        val deleted = change("old.md", null)
        assertEquals(listOf(readme, added, deleted), ShowRenderedDiffAction.markdownChanges(listOf(readme, code, added, deleted)))
    }

    fun `test a file renamed to or from Markdown counts`() {
        val toMarkdown = change("notes.txt", "notes.md")
        val fromMarkdown = change("notes.md", "notes.txt")
        val other = change("notes.txt", "notes.rst")
        assertEquals(listOf(toMarkdown, fromMarkdown), ShowRenderedDiffAction.markdownChanges(listOf(toMarkdown, fromMarkdown, other)))
    }

    fun `test action is hidden without a Markdown change`() {
        val action = ShowRenderedDiffAction()
        for (selected in listOf(emptyArray(), arrayOf(change("Main.kt", "Main.kt")))) {
            val e = event(action, *selected)
            action.update(e)
            assertFalse(selected.contentToString(), e.presentation.isEnabledAndVisible)
        }
    }

    fun `test action is shown for a Markdown change when JCEF is available`() {
        val action = ShowRenderedDiffAction()
        val e = event(action, change("README.md", "README.md"))
        action.update(e)
        assertEquals(JBCefApp.isSupported(), e.presentation.isEnabledAndVisible)
    }

    fun `test the window opens rendered even when diffs default to the text viewer`() {
        val settings = MarkdownReviewSettings.getInstance()
        settings.defaultViewer = DefaultViewer.TEXT
        val place = DiffSettings.getSettings(DefaultViewerOrder.RENDERED_DIFF_PLACE)
        place.diffToolsOrder = listOf(SimpleDiffTool::class.java.canonicalName)

        val readme = change("README.md", "README.md")
        val action = TestAction()
        action.actionPerformed(event(action, change("Main.kt", "Main.kt"), readme))

        assertEquals(listOf(listOf(readme)), action.shown)
        val first = DiffRequestProcessor.getToolOrderFromSettings(place, DiffManagerEx.getInstance().diffTools).first { tool ->
            listOf(RenderedSideBySideTool::class.java, RenderedMergedTool::class.java, SimpleDiffTool::class.java, UnifiedDiffTool::class.java)
                .any { it.isInstance(tool) }
        }
        assertInstanceOf(first, RenderedSideBySideTool::class.java)
    }
}
