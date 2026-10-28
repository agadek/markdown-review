package pl.mdreview.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pl.mdreview.api.BlockInfo
import pl.mdreview.api.RenderedDiff
import pl.mdreview.api.ViewMode
import pl.mdreview.engine.BlockExtractor
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

/** Per-block revert and apply (FR-12) through [RenderedDiffViewer] on real documents. */
class BlockActionsTest : BasePlatformTestCase() {

    private class RecordingView : RenderedDiffView {
        val shown = mutableListOf<Pair<RenderedDiff, BlockActions>>()
        override val component: JComponent = JPanel()
        override fun show(diff: RenderedDiff) = show(diff, BlockActions.NONE)
        override fun show(diff: RenderedDiff, actions: BlockActions) {
            shown += diff to actions
        }
        override fun refreshTheme() = Unit
        override fun goTo(blockIdx: Int) = Unit
        val diff get() = shown.last().first
        val actions get() = shown.last().second
    }

    private lateinit var viewer: RenderedDiffViewer
    private lateinit var view: RecordingView

    override fun tearDown() {
        try {
            MarkdownReviewSettings.getInstance().showBlockActions = true
        } finally {
            super.tearDown()
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

    private var fileCount = 0

    private fun file(text: String): VirtualFile = myFixture.addFileToProject("f${fileCount++}.md", text).virtualFile

    private fun content(text: String) = DiffContentFactory.getInstance().create(project, file(text)) as DocumentContent

    private fun open(left: DiffContent, right: DiffContent, mode: ViewMode = ViewMode.SIDE_BY_SIDE, request: SimpleDiffRequest? = null) {
        view = RecordingView()
        viewer = RenderedDiffViewer(TestDiffContext(project), request ?: SimpleDiffRequest("test", left, right, "left", "right"), mode) { _, _ -> view }
        Disposer.register(testRootDisposable, Disposable { viewer.dispose() })
        viewer.init()
        waitFor { view.shown.isNotEmpty() }
    }

    /** Clicks [action] on [block] of the page on screen and waits for the page that follows the edit. */
    private fun click(action: BlockAction, block: BlockInfo, stamp: String = view.actions.stamp): Boolean {
        val before = view.shown.size
        val done = viewer.performBlockAction(BlockActionRequest(action, block.idx, stamp))
        if (done) waitFor { view.shown.size > before && view.actions.stamp != stamp }
        return done
    }

    private fun fixture(case: String, name: String): String {
        val url = javaClass.getResource("/fixtures/$case/$name") ?: error("Missing fixture $case/$name")
        return BlockExtractor.normalizeLineEndings(File(url.toURI()).readText())
    }

    fun `test both local files get both buttons`() {
        open(content("a\n"), content("b\n"))
        assertTrue(view.actions.revert)
        assertTrue(view.actions.apply)
    }

    fun `test merged view gets revert only`() {
        open(content("a\n"), content("b\n"), ViewMode.MERGED)
        assertTrue(view.actions.revert)
        assertFalse(view.actions.apply)
    }

    fun `test read-only sides get no buttons`() {
        val factory = DiffContentFactory.getInstance()
        open(factory.create(project, "old text\n"), content("new text\n"))
        assertTrue(view.actions.revert)
        assertFalse(view.actions.apply)

        open(content("old text\n"), factory.create(project, "new text\n"))
        assertFalse(view.actions.revert)
        assertTrue(view.actions.apply)
        assertFalse(viewer.performBlockAction(BlockActionRequest(BlockAction.REVERT, view.diff.changes.single().idx, view.actions.stamp)))
    }

    fun `test request marked read-only gets no buttons`() {
        val left = content("old text\n")
        val right = content("new text\n")
        val request = SimpleDiffRequest("test", left, right, "left", "right")
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        open(left, right, request = request)
        assertFalse(view.actions.any)
    }

    fun `test setting turns the buttons off`() {
        MarkdownReviewSettings.getInstance().showBlockActions = false
        open(content("a\n"), content("b\n"))
        assertFalse(view.actions.any)
    }

    fun `test revert and apply a modified block`() {
        val left = content("# Title\n\nold text here\n")
        val right = content("# Title\n\nnew text here\n")
        open(left, right)
        assertTrue(click(BlockAction.REVERT, view.diff.changes.single()))
        assertEquals("# Title\n\nold text here\n", right.document.text)
        assertTrue(view.diff.identical)

        runWriteCommand { right.document.setText("# Title\n\nnewer text here\n") }
        waitFor { view.diff.changes.isNotEmpty() && view.actions.stamp.endsWith(right.document.modificationStamp.toString()) }
        assertTrue(click(BlockAction.APPLY, view.diff.changes.single()))
        assertEquals("# Title\n\nnewer text here\n", left.document.text)
    }

    fun `test insert and delete at the start, middle and end`() {
        val base = "A one\n\nB two\n\nC three\n"
        val variants = listOf("X new\n\n$base", "A one\n\nX new\n\nB two\n\nC three\n", "${base}\nX new\n")
        for (other in variants) {
            // The right side has an extra block: revert deletes it.
            val right = content(other)
            open(content(base), right)
            assertTrue(click(BlockAction.REVERT, view.diff.changes.single()))
            assertEquals(base, right.document.text)

            // The right side lacks the block: revert inserts it.
            val shorter = content(base)
            open(content(other), shorter)
            assertTrue(click(BlockAction.REVERT, view.diff.changes.single()))
            assertEquals(other, shorter.document.text)
        }
    }

    fun `test one undo restores the original and redo reapplies`() {
        val right = content("# Title\n\n```\na\nb\n```\n\nnew paragraph\n")
        open(content("# Title\n"), right)
        val original = right.document.text
        // The code block is removed as a whole, so one click edits several lines at once.
        assertTrue(click(BlockAction.REVERT, view.diff.changes.first()))
        val reverted = right.document.text
        assertEquals("# Title\n\nnew paragraph\n", reverted)

        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        undo.undo(null)
        assertEquals(original, right.document.text)
        assertTrue(undo.isRedoAvailable(null))
        undo.redo(null)
        assertEquals(reverted, right.document.text)
    }

    fun `test a stale page is refused and re-rendered`() {
        val right = content("same\n\nadded\n")
        open(content("same\n"), right)
        val block = view.diff.changes.single()
        val stamp = view.actions.stamp

        runWriteCommand { right.document.insertString(0, "typed ") }
        assertFalse(viewer.performBlockAction(BlockActionRequest(BlockAction.REVERT, block.idx, stamp)))
        assertEquals("typed same\n\nadded\n", right.document.text)
        waitFor { view.actions.stamp != stamp }

        // A request from the old page stays refused after the new page is up.
        assertFalse(viewer.performBlockAction(BlockActionRequest(BlockAction.REVERT, block.idx, stamp)))
        assertEquals("typed same\n\nadded\n", right.document.text)
    }

    fun `test CRLF file keeps its line separators`() {
        val file = file("x")
        runWriteAction { file.setBinaryContent("# Title\r\n\r\nnew text here\r\n\r\nadded\r\n".toByteArray()) }
        val right = DiffContentFactory.getInstance().create(project, file) as DocumentContent
        open(content("# Title\n\nold text here\n"), right)
        while (view.diff.changes.isNotEmpty()) assertTrue(click(BlockAction.REVERT, view.diff.changes.first()))
        assertEquals("# Title\n\nold text here\n", right.document.text)

        FileDocumentManager.getInstance().saveDocument(right.document)
        assertEquals("# Title\r\n\r\nold text here\r\n", String(file.contentsToByteArray()))
    }

    fun `test reverting every change of fixture 18 gives the left side`() {
        val leftText = fixture("18-mixed", "old.md")
        val right = content(fixture("18-mixed", "new.md"))
        open(content(leftText), right)
        var clicks = 0
        while (view.diff.changes.isNotEmpty()) {
            assertTrue(click(BlockAction.REVERT, view.diff.changes.first { it.edit != null }))
            assertTrue("too many clicks", ++clicks < 50)
        }
        assertEquals(leftText, right.document.text)
    }

    fun `test applying every change of fixture 18 gives the right side`() {
        val rightText = fixture("18-mixed", "new.md")
        val left = content(fixture("18-mixed", "old.md"))
        open(left, content(rightText))
        var clicks = 0
        while (view.diff.changes.isNotEmpty()) {
            assertTrue(click(BlockAction.APPLY, view.diff.changes.last { it.edit != null }))
            assertTrue("too many clicks", ++clicks < 50)
        }
        assertEquals(rightText, left.document.text)
    }

    fun `test page message parses into a request`() {
        assertEquals(BlockActionRequest(BlockAction.APPLY, 7, "3:4"), BlockActionRequest.parse("edit|apply|7|3:4"))
        assertNull(BlockActionRequest.parse("edit|delete|7|3:4"))
        assertNull(BlockActionRequest.parse("edit|revert|x|3:4"))
        assertNull(BlockActionRequest.parse("source|left|3"))
    }

    fun `test page adds buttons only to blocks with a region`() {
        open(content("# A\n\nold words\n"), content("# A\n\nnew words\n"))
        val js = HtmlPage.actionsJs(view.diff, view.actions)
        assertTrue(js, js.startsWith("mdr.actions([1], [{action: \"apply\""))
        assertEquals("", HtmlPage.actionsJs(view.diff, BlockActions.NONE))
        assertEquals("\"a\\u0022\\u003c\\u002fscript\\u003e\"", HtmlPage.jsString("a\"</script>"))
    }

    fun `test reverting a moved block moves it back (P2_7)`() {
        val leftText = fixture("04-block-moved", "old.md")
        val right = content(fixture("04-block-moved", "new.md"))
        open(content(leftText), right)
        val original = right.document.text
        // The heading and the paragraph each move back with one click: a delete and an insert in one command.
        assertTrue(click(BlockAction.REVERT, view.diff.changes.first()))
        assertEquals(2, view.diff.changes.size)
        UndoManager.getInstance(project).undo(null)
        assertEquals(original, right.document.text)
        waitFor { view.diff.changes.size == 4 && view.actions.stamp.endsWith(right.document.modificationStamp.toString()) }

        var clicks = 0
        while (view.diff.changes.isNotEmpty()) {
            assertTrue(click(BlockAction.REVERT, view.diff.changes.first()))
            assertTrue("too many clicks", ++clicks < 10)
        }
        assertEquals(2, clicks)
        assertEquals(leftText, right.document.text)
    }

    /** Saves [markdown] as the new source of [block] on [side], as Ctrl/Cmd+Enter in the block's textarea does (P2.6). */
    private fun saveSource(block: BlockInfo, side: String, markdown: String, stamp: String = view.actions.stamp): Boolean {
        val request = BlockActionRequest.parse("edit|edit-$side|${block.idx}|$stamp|$markdown")
        assertNotNull("the page's edit message must parse", request)
        val before = view.shown.size
        val done = viewer.performBlockAction(request!!)
        if (done) waitFor { view.shown.size > before && view.actions.stamp != stamp }
        return done
    }

    fun `test editing a paragraph saves its source as one undo step (P2_6)`() {
        val right = content("# Title\n\nnew text here\n\nlast\n")
        open(content("# Title\n\nold text here\n\nlast\n"), right)
        val original = right.document.text
        assertTrue(saveSource(view.diff.changes.single(), "right", "edited *text* here"))
        assertEquals("# Title\n\nedited *text* here\n\nlast\n", right.document.text)

        val undo = UndoManager.getInstance(project)
        undo.undo(null)
        assertEquals(original, right.document.text)
        undo.redo(null)
        assertEquals("# Title\n\nedited *text* here\n\nlast\n", right.document.text)
    }

    fun `test editing keeps pipes and line breaks of the source (P2_6)`() {
        val right = content("intro\n\nnew text\n")
        open(content("intro\n\nold text\n"), right)
        assertTrue(saveSource(view.diff.changes.single(), "right", "first | line\nsecond line"))
        assertEquals("intro\n\nfirst | line\nsecond line\n", right.document.text)
    }

    fun `test editing a table row saves the row only (P2_6)`() {
        val table = "| Name | Value |\n| --- | --- |\n| a | 1 |\n| b | 2 |\n"
        val right = content(table.replace("| b | 2 |", "| b | 3 |"))
        open(content(table), right)
        assertTrue(saveSource(view.diff.changes.single(), "right", "| b | 4 |"))
        assertEquals(table.replace("| b | 2 |", "| b | 4 |"), right.document.text)
    }

    fun `test editing a list item keeps its nested items (P2_6)`() {
        val right = content("- one\n- two changed\n  - nested\n- three\n")
        open(content("- one\n- two\n  - nested\n- three\n"), right)
        val item = view.diff.changes.single()
        assertTrue(saveSource(item, "right", "- two edited"))
        assertEquals("- one\n- two edited\n  - nested\n- three\n", right.document.text)
    }

    fun `test an unchanged block and the left side can be edited too (P2_6)`() {
        val left = content("# Title\n\nsame words here\n")
        open(left, content("# Title\n\nsame words here\n"))
        assertTrue(saveSource(view.diff.blocks[1], "left", "changed words here"))
        assertEquals("# Title\n\nchanged words here\n", left.document.text)
        assertEquals(1, view.diff.changes.size)
    }

    fun `test editing is refused on read-only sides and stale pages (P2_6)`() {
        val factory = DiffContentFactory.getInstance()
        open(content("old text\n"), factory.create(project, "new text\n"))
        assertFalse(saveSource(view.diff.changes.single(), "right", "edited"))

        val right = content("same\n\nadded\n")
        open(content("same\n"), right)
        val stamp = view.actions.stamp
        runWriteCommand { right.document.insertString(0, "typed ") }
        assertFalse(saveSource(view.diff.blocks[0], "right", "edited", stamp))
        assertEquals("typed same\n\nadded\n", right.document.text)
    }

    fun `test the source a double-click opens is the block's lines on that side (P2_6)`() {
        val right = content("# Title\n\n> a quoted text\n> more\n\n- one\n- two\n  - nested\n")
        open(content("# Title\n\n> a quoted text\n> less\n\n- one\n- two\n  - nested\n"), right)
        val stamp = view.actions.stamp
        val quote = view.diff.changes.single()
        assertEquals("> a quoted text\n> more", viewer.blockSource("right", quote.idx, stamp))
        assertEquals("> a quoted text\n> less", viewer.blockSource("left", quote.idx, stamp))
        assertEquals("- two", viewer.blockSource("right", view.diff.blocks.last().idx - 1, stamp))
        assertNull(viewer.blockSource("right", quote.idx, "0:0"))
    }

    fun `test emptying the editor removes the block (P2_6)`() {
        val right = content("one\n\ntwo\n\nthree\n")
        open(content("one\n\ntwo\n\nthree\n"), right)
        assertTrue(saveSource(view.diff.blocks[1], "right", ""))
        assertEquals("one\n\n\nthree\n", right.document.text)
    }

    fun `test the merged view edits the right side only (P2_6)`() {
        open(content("a\n"), content("b\n"), ViewMode.MERGED)
        assertTrue(view.actions.editRight)
        assertFalse(view.actions.editLeft)
        // "a" and "b" show as a removed and an added block; the removed one is the left side's.
        assertFalse(saveSource(view.diff.changes.first(), "left", "edited"))
    }

    fun `test editing does not need the buttons setting (P2_6)`() {
        MarkdownReviewSettings.getInstance().showBlockActions = false
        open(content("a\n"), content("b\n"))
        assertFalse(view.actions.any)
        assertTrue(view.actions.editLeft && view.actions.editRight)
        assertTrue(HtmlPage.actionsJs(view.diff, view.actions).startsWith("mdr.editing({left: true, right: true}"))
    }

    fun `test the page's edit message keeps pipes in the source (P2_6)`() {
        assertEquals(
            BlockActionRequest(BlockAction.EDIT_RIGHT, 2, "3:4", "| a | b |\n"),
            BlockActionRequest.parse("edit|edit-right|2|3:4|| a | b |\n"),
        )
        assertEquals(BlockActionRequest(BlockAction.EDIT_LEFT, 0, "3:4", ""), BlockActionRequest.parse("edit|edit-left|0|3:4|"))
        assertNull(BlockActionRequest.parse("edit|edit-left|0|3:4"))
        assertNull(BlockActionRequest.parse("edit|revert|0|3:4|text"))
    }

    private fun runWriteCommand(action: () -> Unit) = WriteCommandAction.runWriteCommandAction(project, action)
}
