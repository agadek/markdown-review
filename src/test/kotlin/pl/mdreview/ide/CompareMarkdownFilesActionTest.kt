package pl.mdreview.ide

import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class CompareMarkdownFilesActionTest : BasePlatformTestCase() {

    private class TestAction(private val chosen: List<VirtualFile>) : CompareMarkdownFilesAction() {
        val chooserCalls = mutableListOf<VirtualFile?>()
        val requests = mutableListOf<DiffRequest>()

        override fun chooseFiles(project: Project, selected: VirtualFile?): List<VirtualFile> {
            chooserCalls += selected
            return chosen
        }

        override fun showDiff(project: Project, request: DiffRequest) {
            requests += request
        }
    }

    private fun file(name: String) = myFixture.addFileToProject(name, "# $name\n").virtualFile

    private fun event(action: CompareMarkdownFilesAction, vararg selected: VirtualFile) = TestActionEvent.createTestEvent(
        action,
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*selected))
            .build(),
    )

    private fun comparedFiles(request: DiffRequest): List<VirtualFile> =
        (request as ContentDiffRequest).contents.map { (it as FileContent).file }

    fun `test two selected files open without a chooser`() {
        val a = file("a.md")
        val b = file("b.md")
        val action = TestAction(emptyList())
        action.actionPerformed(event(action, a, b))
        assertEmpty(action.chooserCalls)
        assertEquals(listOf(a, b), comparedFiles(action.requests.single()))
    }

    fun `test one selected file asks for the second`() {
        val a = file("a.md")
        val b = file("b.md")
        val action = TestAction(listOf(b))
        action.actionPerformed(event(action, a))
        assertEquals(listOf<VirtualFile?>(a), action.chooserCalls)
        assertEquals(listOf(a, b), comparedFiles(action.requests.single()))
    }

    fun `test no selection asks for both files`() {
        val a = file("a.md")
        val b = file("b.md")
        val action = TestAction(listOf(a, b))
        action.actionPerformed(event(action))
        assertEquals(listOf<VirtualFile?>(null), action.chooserCalls)
        assertEquals(listOf(a, b), comparedFiles(action.requests.single()))
    }

    fun `test cancelled chooser opens nothing`() {
        val action = TestAction(emptyList())
        action.actionPerformed(event(action, file("a.md")))
        assertEmpty(action.requests)
    }

    fun `test action is enabled for up to two Markdown files`() {
        val action = CompareMarkdownFilesAction()
        for (selected in listOf(emptyArray(), arrayOf(file("a.md")), arrayOf(file("b.md"), file("c.markdown")))) {
            val e = event(action, *selected)
            action.update(e)
            assertTrue(selected.contentToString(), e.presentation.isEnabledAndVisible)
        }
    }

    fun `test action is hidden for other files or more than two`() {
        val action = CompareMarkdownFilesAction()
        for (selected in listOf(arrayOf(file("a.txt")), arrayOf(file("x.md"), file("y.md"), file("z.md")))) {
            val e = event(action, *selected)
            action.update(e)
            assertFalse(selected.contentToString(), e.presentation.isEnabledAndVisible)
        }
    }
}
