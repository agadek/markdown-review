package pl.mdreview.ide

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import org.intellij.plugins.markdown.lang.MarkdownFileType
import pl.mdreview.api.DocumentFormat
import pl.mdreview.ide.asciidoc.AsciiDocPluginFileTypeRecognizer
import javax.swing.Icon

class ReviewFilesTest : BasePlatformTestCase() {

    /** The AsciiDoc plugin's file type, without a dependency on the plugin: only its name is part of the contract. */
    private object FakeAsciiDocFileType : FileType {
        override fun getName() = ASCIIDOC_FILE_TYPE_NAME
        override fun getDescription() = "AsciiDoc"
        override fun getDefaultExtension() = "asc"
        override fun getIcon(): Icon? = null
        override fun isBinary() = false
    }

    override fun tearDown() {
        try {
            AsciiDocSupport.uninstall()
        } finally {
            super.tearDown()
        }
    }

    fun `test Markdown extensions`() {
        for (name in listOf("a.md", "b.MD", "c.markdown", "d.mdown", "e.mkd")) {
            assertEquals(name, DocumentFormat.MARKDOWN, ReviewFiles.formatOf(LightVirtualFile(name, PlainTextFileType.INSTANCE, "")))
        }
    }

    fun `test AsciiDoc extensions`() {
        for (name in listOf("a.adoc", "b.ADOC", "c.asciidoc", "d.AsciiDoc")) {
            assertEquals(name, DocumentFormat.ASCIIDOC, ReviewFiles.formatOf(LightVirtualFile(name, PlainTextFileType.INSTANCE, "")))
        }
    }

    fun `test asc is not an AsciiDoc extension`() {
        assertNull(ReviewFiles.formatOf(LightVirtualFile("notes.asc", PlainTextFileType.INSTANCE, "")))
        assertNull(ReviewFiles.formatOf(VcsUtil.getFilePath("/repo/notes.asc", false)))
    }

    fun `test a file path is recognized by its name`() {
        assertEquals(DocumentFormat.MARKDOWN, ReviewFiles.formatOf(VcsUtil.getFilePath("/repo/README.md", false)))
        assertEquals(DocumentFormat.ASCIIDOC, ReviewFiles.formatOf(VcsUtil.getFilePath("/repo/guide.adoc", false)))
        assertNull(ReviewFiles.formatOf(VcsUtil.getFilePath("/repo/notes.asc", false)))
    }

    fun `test asc is accepted only through the AsciiDoc plugin's file type`() {
        assertNull(ReviewFiles.formatOf(FakeAsciiDocFileType))
        ExtensionTestUtil.maskExtensions(
            ReviewFiles.DOCUMENT_RECOGNIZER_EP,
            listOf(AsciiDocPluginFileTypeRecognizer()),
            testRootDisposable,
        )
        assertEquals(DocumentFormat.ASCIIDOC, ReviewFiles.formatOf(FakeAsciiDocFileType))
        assertEquals(
            DocumentFormat.ASCIIDOC,
            ReviewFiles.formatOf(LightVirtualFile("notes.asc", FakeAsciiDocFileType, "")),
        )
    }

    fun `test the Markdown plugin's file type is recognized through the old extension point`() {
        assertEquals(DocumentFormat.MARKDOWN, ReviewFiles.formatOf(MarkdownFileType.INSTANCE))
        assertTrue(ReviewFiles.MARKDOWN_RECOGNIZER_EP.extensionList.isNotEmpty())
    }

    fun `test support is not needed for the classification but for rendering`() {
        val asciidoc = LightVirtualFile("guide.adoc", PlainTextFileType.INSTANCE, "")
        assertEquals(DocumentFormat.ASCIIDOC, ReviewFiles.formatOf(asciidoc))
        assertFalse(ReviewFiles.isSupported(asciidoc))
        AsciiDocSupport.install(object : AsciiDocSupport.Runtime {
            override fun isReady() = true
            override fun prepare(onReady: () -> Unit) = onReady()
        })
        assertTrue(ReviewFiles.isSupported(asciidoc))
        assertTrue(ReviewFiles.isSupported(LightVirtualFile("README.md", PlainTextFileType.INSTANCE, "")))
        assertFalse(ReviewFiles.isSupported(ReviewFiles.formatOf(PlainTextFileType.INSTANCE)))
    }

    fun `test a common format ignores unknown sides but rejects a mixed pair`() {
        fun path(name: String) = VcsUtil.getFilePath("/repo/$name", false)
        assertEquals(DocumentFormat.MARKDOWN, ReviewFiles.commonFormat(listOf(path("old.md"), path("new.txt"))))
        assertEquals(DocumentFormat.ASCIIDOC, ReviewFiles.commonFormat(listOf(path("old.adoc"), path("new.adoc"))))
        assertNull(ReviewFiles.commonFormat(listOf(path("old.md"), path("new.adoc"))))
        assertNull(ReviewFiles.commonFormat(listOf(path("old.txt"), path("new.rst"))))
    }
}

private const val ASCIIDOC_FILE_TYPE_NAME = "AsciiDoc"
