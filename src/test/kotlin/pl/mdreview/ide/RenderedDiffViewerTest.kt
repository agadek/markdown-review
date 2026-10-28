package pl.mdreview.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContextEx
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.project.Project
import pl.mdreview.api.LineRange
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.jcef.JBCefApp
import pl.mdreview.api.BlockStatus
import pl.mdreview.api.RenderedDiff
import pl.mdreview.api.ViewMode
import javax.swing.JComponent
import javax.swing.JPanel

class RenderedDiffViewerTest : BasePlatformTestCase() {

    fun `test external link schemes are case insensitive`() {
        assertTrue(isExternalLink("HTTPS://example.com/path"))
        assertTrue(isExternalLink("HtTp://example.com/path"))
        assertTrue(isExternalLink("MAILTO:user@example.com"))
        assertFalse(isExternalLink("file:///tmp/readme.md"))
        assertFalse(isExternalLink("ftp://example.com/file"))
    }

    private class RecordingView : RenderedDiffView {
        val shown = mutableListOf<RenderedDiff>()
        val visited = mutableListOf<Int>()
        override val component: JComponent = JPanel()
        override fun show(diff: RenderedDiff) {
            shown += diff
        }
        override fun refreshTheme() = Unit
        override fun goTo(blockIdx: Int) {
            visited += blockIdx
        }
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

    private fun AnAction.isEnabledNow(): Boolean = TestActionEvent.createTestEvent(this).also { update(it) }.presentation.isEnabled

    private fun AnAction.perform() = actionPerformed(TestActionEvent.createTestEvent(this))

    fun `test change navigation moves between changes and stops at the ends`() {
        val request = SimpleDiffRequest(
            "test",
            content("p.md", "one\n\ntwo\n\nthree\n"),
            content("q.md", "one changed\n\ntwo\n\nthree changed\n"),
            "p",
            "q",
        )
        val (viewer, view) = open(request, ViewMode.SIDE_BY_SIDE)
        waitFor { view.shown.isNotEmpty() }
        val changes = view.shown.last().changes.map { it.idx }
        assertEquals(2, changes.size)
        val (next, previous) = viewer.navigationActions

        assertTrue(next.isEnabledNow())
        assertFalse(previous.isEnabledNow())
        next.perform()
        next.perform()
        assertFalse(next.isEnabledNow())
        assertTrue(previous.isEnabledNow())
        previous.perform()
        assertEquals(listOf(changes[0], changes[1], changes[0]), view.visited)
    }

    fun `test change navigation is bound to the shortcuts of the diff actions`() {
        val request = SimpleDiffRequest("test", content("r.md", "a\n"), content("s.md", "b\n"), "r", "s")
        val (viewer, _) = open(request, ViewMode.SIDE_BY_SIDE)
        assertSameElements(ActionUtil.getActions(viewer.component), viewer.navigationActions)
    }

    /** A diff window's context that records each reopen and what the request carried at that moment. */
    private class ReopeningContext(private val project: Project) : DiffContextEx() {
        lateinit var request: SimpleDiffRequest
        val reopens = mutableListOf<Pair<Boolean?, com.intellij.openapi.util.Pair<Side, Int>?>>()
        override fun getProject() = project
        override fun isWindowFocused() = false
        override fun isFocusedInWindow() = false
        override fun requestFocusInWindow() = Unit
        override fun reopenDiffRequest() {
            reopens += getUserData(RenderedMarkdownDiffTool.TEXT_VIEWER_ONCE) to request.getUserData(DiffUserDataKeys.SCROLL_TO_LINE)
        }
        override fun reloadDiffRequest() = Unit
        override fun showProgressBar(enabled: Boolean) = Unit
        override fun setWindowTitle(title: String) = Unit
    }

    private fun names(actions: List<AnAction>) = actions.map { it.templatePresentation.text }

    fun `test a block's menu outside a diff window has go to source only (P3_4)`() {
        val request = SimpleDiffRequest("test", content("m1.md", "a\n"), content("m2.md", "b\n"), "m1", "m2")
        val (viewer, _) = open(request, ViewMode.SIDE_BY_SIDE)
        // Light test files are not under version control, so there is no history either.
        assertEquals(listOf("Go to Source"), names(viewer.blockMenuActions(1, LineRange(0, 1))))
    }

    fun `test export is on the toolbar, not only in the options menu (P3_6)`() {
        val request = SimpleDiffRequest("test", content("x1.md", "a\n"), content("x2.md", "b\n"), "x1", "x2")
        val viewer = RenderedDiffViewer(TestDiffContext(project), request, ViewMode.SIDE_BY_SIDE) { _, _ -> RecordingView() }
        Disposer.register(testRootDisposable, Disposable { viewer.dispose() })
        val toolbar = viewer.init()
        assertEquals(listOf<AnAction>(viewer.exportGroup), toolbar.toolbarActions)
        assertTrue(viewer.exportGroup.isPopup)
        assertNotNull(viewer.exportGroup.templatePresentation.icon)
        assertEquals(listOf("Export as HTML...", "Export as PDF..."), names(viewer.exportActions))
        assertEquals(viewer.exportActions, viewer.exportGroup.childActionsOrStubs.toList())
    }

    fun `test open in text diff reopens the window once in the text viewer at the block's line (P3_4)`() {
        val context = ReopeningContext(project)
        val request = SimpleDiffRequest("test", content("t1.md", "# A\n\nold\n"), content("t2.md", "# A\n\nnew\n"), "t1", "t2")
        context.request = request
        val view = RecordingView()
        val viewer = RenderedDiffViewer(context, request, ViewMode.SIDE_BY_SIDE) { _, _ -> view }
        Disposer.register(testRootDisposable, Disposable { viewer.dispose() })
        viewer.init()
        assertEquals(listOf("Go to Source", "Open in Text Diff Here"), names(viewer.blockMenuActions(1, LineRange(2, 3))))

        viewer.openTextDiffAt(1, 2)
        assertEquals(listOf(true to com.intellij.openapi.util.Pair.create(Side.RIGHT, 2)), context.reopens)
        assertFalse(RenderedSideBySideTool().canShow(context, request))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        // Right after, the rendered viewers are back in the switcher and the scroll target is gone.
        assertNull(context.getUserData(RenderedMarkdownDiffTool.TEXT_VIEWER_ONCE))
        assertNull(request.getUserData(DiffUserDataKeys.SCROLL_TO_LINE))
    }
}
