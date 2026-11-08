package pl.mdreview.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.jcef.JBCefApp
import org.intellij.plugins.markdown.lang.MarkdownFileType
import pl.mdreview.api.DocumentFormat
import javax.swing.Icon

class MarkdownDiffToolsTest : BasePlatformTestCase() {

    private val factory get() = DiffContentFactory.getInstance()

    override fun tearDown() {
        try {
            AsciiDocSupport.uninstall()
        } finally {
            super.tearDown()
        }
    }

    private fun file(name: String, text: String): DiffContent =
        factory.create(project, myFixture.addFileToProject(name, text).virtualFile)

    private fun request(vararg contents: DiffContent) = when (contents.size) {
        2 -> SimpleDiffRequest("test", contents[0], contents[1], "left", "right")
        3 -> SimpleDiffRequest("test", contents[0], contents[1], contents[2], "left", "base", "right")
        else -> error("unsupported")
    }

    private class FakeRuntime : AsciiDocSupport.Runtime {
        override fun isReady() = true
        override fun prepare(onReady: () -> Unit) = onReady()
    }

    private fun <T> withAsciiDoc(block: () -> T): T {
        AsciiDocSupport.install(FakeRuntime())
        try {
            return block()
        } finally {
            AsciiDocSupport.uninstall()
        }
    }

    fun `test two Markdown documents are accepted`() {
        assertTrue(RenderedMarkdownDiffTool.accepts(request(file("a.md", "# A"), file("b.md", "# B"))))
    }

    fun `test other markdown extensions are accepted`() {
        assertTrue(RenderedMarkdownDiffTool.accepts(request(file("a.markdown", "x"), file("b.mdown", "y"))))
    }

    fun `test other file types are rejected`() {
        assertFalse(RenderedMarkdownDiffTool.accepts(request(file("a.txt", "a"), file("b.txt", "b"))))
        assertFalse(RenderedMarkdownDiffTool.accepts(request(file("A.java", "class A {}"), file("B.java", "class B {}"))))
    }

    fun `test a Markdown file renamed to an unknown type is still accepted`() {
        assertTrue(RenderedMarkdownDiffTool.accepts(request(file("a.md", "# A"), file("b.txt", "plain"))))
        assertTrue(RenderedMarkdownDiffTool.accepts(request(file("a.txt", "plain"), file("b.md", "# B"))))
    }

    fun `test AsciiDoc is rejected while AsciiDoc support is unavailable`() {
        assertFalse(AsciiDocSupport.isAvailable())
        assertFalse(RenderedMarkdownDiffTool.accepts(request(file("a.adoc", "= A"), file("b.adoc", "= B"))))
        assertFalse(RenderedMarkdownDiffTool.accepts(request(EmptyContent(), file("new.adoc", "= New"))))
    }

    fun `test AsciiDoc is accepted while AsciiDoc support is available`() = withAsciiDoc {
        assertTrue(RenderedMarkdownDiffTool.accepts(request(file("a.adoc", "= A"), file("b.adoc", "= B"))))
        assertTrue(RenderedMarkdownDiffTool.accepts(request(file("c.asciidoc", "= C"), file("d.adoc", "= D"))))
        assertTrue(RenderedMarkdownDiffTool.accepts(request(EmptyContent(), file("new.adoc", "= New"))))
    }

    fun `test a mixed Markdown and AsciiDoc pair is rejected`() = withAsciiDoc {
        val mixed = request(file("a.md", "# A"), file("b.adoc", "= B"))
        assertFalse(RenderedMarkdownDiffTool.accepts(mixed))
        assertFalse(RenderedMarkdownDiffTool.accepts(request(file("c.adoc", "= C"), file("d.md", "# D"))))
        assertNull(RenderedMarkdownDiffTool.formatOf(mixed.contents))
    }

    fun `test the format of a request is the common format of its sides`() = withAsciiDoc {
        assertEquals(
            DocumentFormat.MARKDOWN,
            RenderedMarkdownDiffTool.formatOf(request(file("a.md", "# A"), file("b.md", "# B")).contents),
        )
        assertEquals(
            DocumentFormat.ASCIIDOC,
            RenderedMarkdownDiffTool.formatOf(request(file("a.adoc", "= A"), file("b.adoc", "= B")).contents),
        )
    }

    fun `test empty content on one side is accepted`() {
        assertTrue(RenderedMarkdownDiffTool.accepts(request(EmptyContent(), file("new.md", "# New"))))
        assertTrue(RenderedMarkdownDiffTool.accepts(request(file("old.md", "# Old"), EmptyContent())))
    }

    fun `test empty content on both sides is rejected`() {
        assertFalse(RenderedMarkdownDiffTool.accepts(request(EmptyContent(), EmptyContent())))
    }

    fun `test three-way requests are rejected`() {
        assertFalse(RenderedMarkdownDiffTool.accepts(request(file("a.md", "a"), file("b.md", "b"), file("c.md", "c"))))
    }

    fun `test requests without contents are rejected`() {
        assertFalse(RenderedMarkdownDiffTool.accepts(MessageDiffRequest("nothing to show")))
    }

    fun `test binary content is rejected`() {
        val binary = factory.createBinary(project, byteArrayOf(1, 2, 3), UnknownFileType.INSTANCE, "a.bin")
        assertFalse(RenderedMarkdownDiffTool.accepts(request(binary, file("b.md", "# B"))))
    }

    fun `test Markdown file type from the Markdown plugin is accepted without an extension`() {
        val left = factory.create(project, "# A", MarkdownFileType.INSTANCE)
        val right = factory.create(project, "# B", MarkdownFileType.INSTANCE)
        assertTrue(RenderedMarkdownDiffTool.accepts(request(left, right)))
        assertTrue(ReviewFiles.MARKDOWN_RECOGNIZER_EP.extensionList.isNotEmpty())
        assertTrue(ReviewFiles.isSupported(ReviewFiles.formatOf(MarkdownFileType.INSTANCE)))
    }

    fun `test canShow also needs JCEF`() {
        val request = request(file("a.md", "# A"), file("b.md", "# B"))
        val context = TestDiffContext(project)
        assertEquals(JBCefApp.isSupported(), RenderedSideBySideTool().canShow(context, request))
        assertEquals(JBCefApp.isSupported(), RenderedMergedTool().canShow(context, request))
    }

    fun `test canShow for AsciiDoc also needs support and JCEF`() = withAsciiDoc {
        val request = request(file("a.adoc", "= A"), file("b.adoc", "= B"))
        val context = TestDiffContext(project)
        assertEquals(JBCefApp.isSupported(), RenderedSideBySideTool().canShow(context, request))
        assertEquals(JBCefApp.isSupported(), RenderedMergedTool().canShow(context, request))
    }

    fun `test tool names and tooltips come from the bundle`() {
        assertEquals("Rendered (side by side)", RenderedSideBySideTool().name)
        assertEquals("Rendered (merged)", RenderedMergedTool().name)
        assertTrue(RenderedSideBySideTool().getTooltip().contains("Markdown"))
        assertTrue(RenderedSideBySideTool().getTooltip().contains("AsciiDoc"))
        assertTrue(RenderedMergedTool().getTooltip().contains("Markdown"))
        assertTrue(RenderedMergedTool().getTooltip().contains("AsciiDoc"))
    }

    fun `test each rendered viewer has its own switcher icon`() {
        assertSame(MarkdownReviewIcons.RENDERED_SIDE_BY_SIDE, RenderedSideBySideTool().getIcon())
        assertSame(MarkdownReviewIcons.RENDERED_MERGED, RenderedMergedTool().getIcon())
        for (name in listOf("renderedSideBySide", "renderedMerged")) {
            for (variant in listOf("", "_dark")) {
                val path = "/pl/mdreview/icons/$name$variant.svg"
                assertNotNull(path, MarkdownReviewIcons::class.java.getResource(path))
            }
        }
    }

    fun `test getIcon has the signature of FrameDiffTool getIcon in 2026_2`() {
        // The platform calls it through the interface's default method, so only an exact JVM signature overrides it.
        val method = RenderedMarkdownDiffTool::class.java.getMethod("getIcon")
        assertEquals(Icon::class.java, method.returnType)
    }
}

class TestDiffContext(private val project: Project?) : DiffContext() {
    override fun getProject() = project
    override fun isWindowFocused() = false
    override fun isFocusedInWindow() = false
    override fun requestFocusInWindow() = Unit
}
