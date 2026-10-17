package pl.mdreview.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.jcef.JBCefApp
import pl.mdreview.api.BlockStatus
import pl.mdreview.api.RenderedDiff
import pl.mdreview.api.ViewMode
import javax.swing.JComponent
import javax.swing.JPanel

class RenderedDiffViewerTest : BasePlatformTestCase() {

    private class RecordingView : RenderedDiffView {
        val shown = mutableListOf<RenderedDiff>()
        override val component: JComponent = JPanel()
        override fun show(diff: RenderedDiff) {
            shown += diff
        }
        override fun refreshTheme() = Unit
        override fun goTo(blockIdx: Int) = Unit
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for the viewer to render" }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
    }

    private fun content(name: String, text: String) =
        DiffContentFactory.getInstance().create(project, myFixture.addFileToProject(name, text).virtualFile) as DocumentContent

    private fun open(request: SimpleDiffRequest, mode: ViewMode): Pair<RenderedDiffViewer, RecordingView> {
        val view = RecordingView()
        val viewer = RenderedDiffViewer(TestDiffContext(project), request, mode) { _, _ -> view }
        Disposer.register(testRootDisposable, Disposable { viewer.dispose() })
        viewer.init()
        return viewer to view
    }

    fun `test viewer renders a simple diff request`() {
        val request = SimpleDiffRequest("test", content("old.md", "# Title\n\nold text\n"), content("new.md", "# Title\n\nnew text\n"), "old", "new")
        val (_, view) = open(request, ViewMode.SIDE_BY_SIDE)
        waitFor { view.shown.isNotEmpty() }
        val diff = view.shown.last()
        assertEquals(listOf(BlockStatus.MODIFIED), diff.changes.map { it.status })
        assertTrue(diff.bodyHtml.contains("data-side=\"left\""))
    }

    fun `test merged viewer renders an added file`() {
        val request = SimpleDiffRequest("test", EmptyContent(), content("new.md", "# New\n\ntext\n"), "old", "new")
        val (_, view) = open(request, ViewMode.MERGED)
        waitFor { view.shown.isNotEmpty() }
        assertTrue(view.shown.last().changes.all { it.status == BlockStatus.ADDED })
    }

    fun `test viewer re-renders after a document change`() {
        val right = content("b.md", "same\n")
        val request = SimpleDiffRequest("test", content("a.md", "same\n"), right, "a", "b")
        val (_, view) = open(request, ViewMode.SIDE_BY_SIDE)
        waitFor { view.shown.isNotEmpty() }
        assertTrue(view.shown.last().changes.isEmpty())

        WriteCommandAction.runWriteCommandAction(project) { right.document.setText("same\n\nadded\n") }
        waitFor { view.shown.size >= 2 }
        assertEquals(listOf(BlockStatus.ADDED), view.shown.last().changes.map { it.status })
    }

    fun `test tool creates a viewer for a simple diff request`() {
        if (JBCefApp.isSupported()) return // the default view needs a real browser; covered by the tests above
        val request = SimpleDiffRequest("test", content("x.md", "a\n"), content("y.md", "b\n"), "x", "y")
        val viewer = RenderedSideBySideTool().createComponent(TestDiffContext(project), request)
        try {
            assertInstanceOf(viewer, RenderedDiffViewer::class.java)
            viewer.init()
            assertNotNull(viewer.component)
        } finally {
            viewer.dispose()
        }
    }
}
