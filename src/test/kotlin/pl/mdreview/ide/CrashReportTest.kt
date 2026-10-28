package pl.mdreview.ide

import java.net.URI
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The prefilled GitHub issue link of the crash reporter (P3.1). */
class CrashReportTest {

    private val trace = """
        java.lang.IllegalStateException: Boom in /home/alice/project/README.md
        	at pl.mdreview.engine.BlockExtractor.extract(BlockExtractor.kt:42)
        	at pl.mdreview.render.CoreMarkdownDiffRenderer.render(CoreMarkdownDiffRenderer.kt:17)
    """.trimIndent()

    private fun params(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val (key, value) = it.split('=', limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }

    @Test
    fun `fills the crash template's fields`() {
        val url = CrashReport.url(trace, "Opened the diff of README.md", "IDE: IntelliJ IDEA\nPlugin: 1.2.0", home = "/home/alice")
        assertTrue(url.startsWith("https://github.com/agadek/markdown-review/issues/new?template=crash_report.yml&"))
        val p = params(url)
        assertEquals("Crash: java.lang.IllegalStateException: Boom in ~/project/README.md", p["title"])
        assertEquals("Opened the diff of README.md", p["steps"])
        assertEquals("IDE: IntelliJ IDEA\nPlugin: 1.2.0", p["environment"])
        assertEquals(trace.replace("/home/alice", "~"), p["stacktrace"])
        // Spaces as %20: a literal + would stay a plus sign in some decoders.
        assertFalse(url.contains('+'))
    }

    @Test
    fun `a long exception is cut at a line to fit the link`() {
        val long = trace + "\n" + (1..2000).joinToString("\n") { "\tat pl.mdreview.Frame$it.call(Frame.kt:$it)" }
        val url = CrashReport.url(long, null, "env", home = null)
        assertTrue(url.length <= CrashReport.MAX_URL_LENGTH, "${url.length}")
        val stacktrace = params(url).getValue("stacktrace")
        assertTrue(stacktrace.startsWith(trace))
        assertTrue(stacktrace.endsWith(CrashReport.TRUNCATED))
        assertEquals("", params(url)["steps"])
    }

    @Test
    fun `a long first line gives a short title`() {
        val url = CrashReport.url("java.lang.RuntimeException: " + "x".repeat(500), null, "env", home = null)
        val title = params(url).getValue("title")
        assertEquals(120 + "Crash: ".length, title.length)
        assertTrue(title.endsWith("..."))
    }
}
