package pl.mdreview.ide

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.jcef.JBCefApp
import com.intellij.vcsUtil.VcsUtil
import pl.mdreview.api.ViewMode

/** "Export Rendered Diff..." for the selected changes (P3.6); the hidden browser is left to manual checks. */
class ExportRenderedDiffActionTest : BasePlatformTestCase() {

    private class Revision(private val path: FilePath, private val text: String?) : ContentRevision {
        override fun getContent() = text
        override fun getFile() = path
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.NULL
    }

    private fun path(name: String) = VcsUtil.getFilePath("/repo/$name", false)

    private fun change(before: String?, after: String?, old: String? = "# Title\n\nold text\n", new: String? = "# Title\n\nnew text\n") =
        Change(before?.let { Revision(path(it), old) }, after?.let { Revision(path(it), new) })

    private fun render(change: Change) =
        ExportRenderedDiffAction().render(project, change, ViewMode.SIDE_BY_SIDE, MarkdownReviewSettings.getInstance())

    fun `test each kind of change is rendered with its label`() {
        val modified = render(change("README.md", "README.md"))
        assertEquals("Modified", modified.change)
        assertTrue(modified.path.endsWith("README.md"))
        assertTrue(modified.diff!!.bodyHtml.contains("<ins class=\"mdr-ins\">new</ins>"))

        assertEquals("Added", render(change(null, "docs/new.md")).change)
        assertEquals("Deleted", render(change("old.md", null)).change)
        assertEquals("Renamed from a.md", render(change("a.md", "b.md")).change)
    }

    fun `test a revision without content becomes a note instead of failing the export`() {
        val file = render(change("README.md", "README.md", old = null))
        assertNull(file.diff)
        assertEquals("Cannot load the content of README.md", file.error)
    }

    fun `test the files combine into one page with a section each`() {
        val files = listOf(render(change("a.md", "a.md")), render(change("b.md", "b.md", new = "# Title\n\nold text\n")))
        val page = HtmlExport.combine("Markdown changes (2 files)", files, "No differences")
        assertEquals(2, Regex("<section class=\"mdr-export-file\"").findAll(page.bodyHtml).count())
        assertTrue(page.bodyHtml.contains("<p class=\"mdr-export-note\">No differences</p>"))
        assertTrue(page.css.contains(".mdr-export-toc"))
        assertTrue(HtmlPage.build(page, "void 0", 0).contains("<h1 class=\"mdr-export-title\">Markdown changes (2 files)</h1>"))
    }

    fun `test action shows only for Markdown changes`() {
        val action = ExportRenderedDiffAction()
        fun visible(vararg selected: Change): Boolean {
            val e = TestActionEvent.createTestEvent(
                action,
                SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(VcsDataKeys.SELECTED_CHANGES, arrayOf(*selected)).build(),
            )
            action.update(e)
            return e.presentation.isEnabledAndVisible
        }
        assertFalse(visible(change("Main.kt", "Main.kt")))
        assertEquals(JBCefApp.isSupported(), visible(change("Main.kt", "Main.kt"), change("README.md", "README.md")))
    }
}
