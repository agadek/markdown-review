package pl.mdreview.render

import org.asciidoctor.Asciidoctor
import pl.mdreview.api.DocumentFormat
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
import pl.mdreview.engine.asciidoc.AsciidoctorProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Performance check for NFR-2 and NFR-12: diff + render of generated 1,000- and 10,000-line documents, Markdown
 * and AsciiDoc.
 *
 * The timings depend on the machine, so they are reported, not asserted: the results go to the test
 * output and to `build/reports/benchmark/render.md`, which CI adds to the job summary. The targets are
 * 300 ms for 1,000 lines and 2 s for 10,000 lines on a typical laptop, after the first AsciiDoc render has started
 * Asciidoctor.
 */
class RenderBenchmarkTest {

    private val renderer = CoreMarkdownDiffRenderer()

    private val asciidoctor = object : AsciidoctorProvider {
        private val instance: Asciidoctor by lazy { Asciidoctor.Factory.create() }
        override fun asciidoctor(): Asciidoctor = instance
    }

    private val asciidocRenderer = CoreMarkdownDiffRenderer(asciidoctor)

    private class Case(val lines: Int, val targetMs: Long, val runs: Int)

    @Test
    fun `render time of generated documents`() {
        val cases = listOf(Case(1_000, 300, 7), Case(10_000, 2_000, 3))
        val report = StringBuilder()
        report.append("## Render benchmark (NFR-2)\n\n")
        report.append("| Lines | Mode | Median | Best | Target | Within target |\n")
        report.append("|------:|------|-------:|-----:|-------:|:-------------:|\n")
        for (case in cases) {
            val old = generate(case.lines, edited = false)
            val new = generate(case.lines, edited = true)
            benchmark(report, case, old, new, renderer) { RenderRequest(it.first, it.second, it.third) }
        }
        report.append("\n## AsciiDoc render benchmark (NFR-12)\n\n")
        report.append("| Lines | Mode | Median | Best | Target | Within target |\n")
        report.append("|------:|------|-------:|-----:|-------:|:-------------:|\n")
        for (case in cases) {
            val old = generateAsciiDoc(case.lines, edited = false)
            val new = generateAsciiDoc(case.lines, edited = true)
            benchmark(report, case, old, new, asciidocRenderer) {
                RenderRequest(it.first, it.second, it.third, format = DocumentFormat.ASCIIDOC)
            }
        }
        println(report)
        val dir = File("build/reports/benchmark")
        if (dir.mkdirs() || dir.isDirectory) File(dir, "render.md").writeText(report.toString())
    }

    /** Runs [target] on [old]/[new] and appends one row per view mode. */
    private fun benchmark(
        report: StringBuilder,
        case: Case,
        old: String,
        new: String,
        target: CoreMarkdownDiffRenderer,
        request: (Triple<String, String, ViewMode>) -> RenderRequest,
    ) {
        for (mode in ViewMode.entries) {
            val r = request(Triple(old, new, mode))
            repeat(2) { target.render(r) } // warm-up: class loading and JIT
            val times = (1..case.runs).map {
                val start = System.nanoTime()
                val result = target.render(r)
                val ms = (System.nanoTime() - start) / 1_000_000
                assertTrue(result.changes.isNotEmpty())
                ms
            }.sorted()
            val median = times[times.size / 2]
            val ok = if (median <= case.targetMs) "yes" else "no"
            report.append("| ${case.lines} | $mode | $median ms | ${times.first()} ms | ${case.targetMs} ms | $ok |\n")
        }
    }

    /**
     * About [lines] lines of a typical README-like document: sections with a heading, paragraphs with
     * links and inline formatting, a list, a code block and a table. The edited version changes words
     * in every 7th section, adds a list item in every 11th and drops a paragraph in every 13th.
     */
    private fun generate(lines: Int, edited: Boolean): String {
        val out = StringBuilder()
        var section = 0
        var count = 0
        while (count < lines) {
            val sb = StringBuilder()
            section++
            val change = edited && section % 7 == 0
            sb.append("## Section $section\n\n")
            sb.append("This is the **first** paragraph of section $section, with a [link](https://example.com/$section) ")
            sb.append(if (change) "and some revised words" else "and some plain words").append(" to compare.\n")
            sb.append("It wraps onto a second line with `inline code` and _emphasis_.\n\n")
            if (!(edited && section % 13 == 0)) {
                sb.append("A second paragraph that stays the same in most sections of the document.\n\n")
            }
            sb.append("- First item of section $section\n- Second item\n")
            if (edited && section % 11 == 0) sb.append("- Added item\n")
            sb.append("- Third item\n\n")
            sb.append("```kotlin\nfun section$section() {\n    println(\"").append(if (change) "changed" else "hello")
                .append("\")\n}\n```\n\n")
            sb.append("| Key | Value |\n|-----|-------|\n| a | $section |\n| b | ").append(if (change) "x" else "y")
                .append(" |\n\n")
            count += sb.count { it == '\n' }
            out.append(sb)
        }
        return out.toString()
    }

    /**
     * About [lines] lines of a typical AsciiDoc document: sections with a heading, paragraphs with links and inline
     * formatting, a list, a source block and a table. The edited version changes words in every 7th section, adds a
     * list item in every 11th, a paragraph in every 13th and changes a code line in every 17th.
     */
    private fun generateAsciiDoc(lines: Int, edited: Boolean): String {
        val out = StringBuilder()
        out.append("= Generated AsciiDoc\n\nA generated document for the AsciiDoc render benchmark.\n\n")
        var section = 0
        var count = out.count { it == '\n' }
        while (count < lines) {
            val sb = StringBuilder()
            section++
            val change = edited && section % 7 == 0
            sb.append("== Section $section\n\n")
            sb.append("This is the *first* paragraph of section $section, with https://example.com/$section[a link] ")
            sb.append(if (change) "and some revised words" else "and some plain words").append(" to compare.\n")
            sb.append("It wraps onto a second line with `inline code` and _emphasis_.\n\n")
            if (edited && section % 13 == 0) {
                sb.append("A paragraph that was added for the benchmark.\n\n")
            }
            sb.append("A second paragraph that stays the same in most sections of the document.\n\n")
            sb.append("* First item of section $section\n* Second item\n")
            if (edited && section % 11 == 0) sb.append("* Added item\n")
            sb.append("* Third item\n\n")
            sb.append("[source,kotlin]\n----\nfun section$section() {\n    println(\"")
                .append(if (edited && section % 17 == 0) "changed" else "hello").append("\")\n}\n----\n\n")
            sb.append("[cols=\"1,1\",options=\"header\"]\n|===\n| Key | Value\n\n")
            sb.append("| a | $section\n| b | ").append(if (change) "x" else "y").append("\n|===\n\n")
            count += sb.count { it == '\n' }
            out.append(sb)
        }
        return out.toString()
    }
}
