package pl.mdreview.ide

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The standalone HTML export (P3.6) and the custom stylesheet it carries. */
class HtmlExportTest {

    private val snapshot = """
        <!DOCTYPE html>
        <html class="mdr-theme-dark"><head><meta charset="utf-8">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src file: data:; style-src 'unsafe-inline'; script-src 'nonce-abc'">
        <style>:root { --md-bg: #2b2b2b; }</style>
        </head><body>
        <div class="mdr-root"><p><img src="file:///docs/img/logo.png" alt="logo"> <img src="file:///docs/a%20b.svg?x=1&amp;y=2"></p>
        <img src="file:///docs/missing.png"> <img src="file:///docs/notes.txt"> <img src="https://example.com/x.png"></div>
        <script>leftOver()</script>
        </body></html>
    """.trimIndent()

    private val images = mapOf(Path.of("/docs/img/logo.png") to byteArrayOf(1, 2, 3), Path.of("/docs/a b.svg") to "<svg/>".toByteArray())

    private fun finish(remoteImages: Boolean = false) = HtmlExport.finish(snapshot, "README.md <diff>", remoteImages) { images[it] }

    @Test
    fun `the exported page allows no scripts and loads nothing from the network`() {
        val html = finish()
        val csp = Regex("Content-Security-Policy\" content=\"([^\"]+)\"").find(html)!!.groupValues[1]
        assertEquals("default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:", csp)
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("nonce"))
        assertContains(html, "<style>:root { --md-bg: #2b2b2b; }</style>")
        assertContains(html, "<html class=\"mdr-theme-dark\">")
    }

    @Test
    fun `remote images stay allowed only when the IDE allowed them`() {
        val csp = Regex("Content-Security-Policy\" content=\"([^\"]+)\"").find(finish(remoteImages = true))!!.groupValues[1]
        assertContains(csp, "img-src data: https: http:;")
    }

    @Test
    fun `local images are inlined and others stay as they are`() {
        val html = finish()
        assertContains(html, "<img src=\"data:image/png;base64,${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}\" alt=\"logo\">")
        assertContains(html, "<img src=\"data:image/svg+xml;base64,${Base64.getEncoder().encodeToString("<svg/>".toByteArray())}\">")
        assertContains(html, "<img src=\"file:///docs/missing.png\">")
        assertContains(html, "<img src=\"file:///docs/notes.txt\">")
        assertContains(html, "<img src=\"https://example.com/x.png\">")
    }

    @Test
    fun `the page gets an escaped title`() {
        val html = finish()
        assertContains(html, "<meta charset=\"utf-8\"><title>README.md &lt;diff&gt;</title>")
        assertEquals(1, Regex("<title>").findAll(HtmlExport.finish(html, "Other")).count())
        assertContains(HtmlExport.finish(html, "Other"), "<title>Other</title>")
    }

    @Test
    fun `default file names`() {
        assertEquals("README.diff.html", HtmlExport.defaultFileName("README.md", "html"))
        assertEquals("notes.v2.diff.pdf", HtmlExport.defaultFileName("notes.v2.md", "pdf"))
        assertEquals("markdown.diff.html", HtmlExport.defaultFileName(null, "html"))
    }

    @Test
    fun `custom stylesheet is read, kept safe inside style and re-read when it changes`() {
        val dir = Files.createTempDirectory("mdr-css")
        try {
            val css = dir.resolve("docs.css")
            Files.writeString(css, "body { color: red; } /* </style><script>x()</script> */")
            val loaded = UserStylesheet.load(" $css ")!!
            assertFalse(loaded.contains("</"))
            assertTrue(loaded.startsWith("body { color: red; }"))
            Files.writeString(css, "body { color: blue; padding: 1px; }")
            Files.setLastModifiedTime(css, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            assertEquals("body { color: blue; padding: 1px; }", UserStylesheet.load(css.toString()))
            assertNull(UserStylesheet.load(""))
            assertNull(UserStylesheet.load(dir.resolve("missing.css").toString()))
            assertNull(UserStylesheet.load(dir.toString()))
            Files.write(css, ByteArray(UserStylesheet.MAX_BYTES + 1) { 'a'.code.toByte() })
            assertNull(UserStylesheet.load(css.toString()))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
