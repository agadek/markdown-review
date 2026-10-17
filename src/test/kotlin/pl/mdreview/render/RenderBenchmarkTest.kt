package pl.mdreview.render

import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Performance check for NFR-2: diff + render of generated 1,000- and 10,000-line documents.
 *
 * The timings depend on the machine, so they are reported, not asserted: the results go to the test
 * output and to `build/reports/benchmark/render.md`, which CI adds to the job summary. The targets are
 * 300 ms for 1,000 lines and 2 s for 10,000 lines on a typical laptop.
 */
class RenderBenchmarkTest {

    private val renderer = CoreMarkdownDiffRenderer()

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
            for (mode in ViewMode.entries) {
                val request = RenderRequest(old, new, mode)
                repeat(2) { renderer.render(request) } // warm-up: class loading and JIT
                val times = (1..case.runs).map {
                    val start = System.nanoTime()
                    val result = renderer.render(request)
                    val ms = (System.nanoTime() - start) / 1_000_000
                    assertTrue(result.changes.isNotEmpty())
                    ms
                }.sorted()
                val median = times[times.size / 2]
                val ok = if (median <= case.targetMs) "yes" else "no"
                report.append("| ${case.lines} | $mode | $median ms | ${times.first()} ms | ${case.targetMs} ms | $ok |\n")
            }
        }
        println(report)
        val dir = File("build/reports/benchmark")
        if (dir.mkdirs() || dir.isDirectory) File(dir, "render.md").writeText(report.toString())
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
}
