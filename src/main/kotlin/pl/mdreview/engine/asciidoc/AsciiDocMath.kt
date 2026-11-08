package pl.mdreview.engine.asciidoc

import pl.mdreview.engine.HtmlFragment

/**
 * Inline formulas in AsciiDoc (P4.7).
 *
 * Asciidoctor converts `latexmath:[...]`, `asciimath:[...]` and `stem:[...]` to MathJax delimiters in the inline HTML:
 * `\(...\)` for LaTeX and `\$...\$` for AsciiMath. The converted HTML does not say which macro produced a formula, so
 * the source of the block is scanned first for the macros in order; the matching converted math is then replaced by
 * an inert `.mdr-math` element for the page script, as for Markdown formulas.
 *
 * LaTeX is drawn by KaTeX. AsciiMath stays source, marked `.mdr-math-failed` with [ASCIIMATH_UNSUPPORTED], and is not
 * an atom of the block's plain text, so the word diff compares it as text.
 */
internal object AsciiDocMath {

    const val LATEX = "latexmath"
    const val ASCIIMATH = "asciimath"
    const val ASCIIMATH_UNSUPPORTED = "AsciiMath is not supported"

    /** Block style drawn by KaTeX: `[latexmath]`, or `[stem]` when the document's `stem` is `latexmath`. */
    fun isLatexStyle(style: String?): Boolean = style?.equals(LATEX, ignoreCase = true) == true

    /** `stem:[...]` is LaTeX only when the document's `stem` attribute is `latexmath`; Asciidoctor's default is AsciiMath. */
    fun isLatexStem(stem: String?): Boolean = stem?.equals(LATEX, ignoreCase = true) == true

    /**
     * [html] with each formula found in [source] replaced by a `.mdr-math` element. Formulas that Asciidoctor did not
     * convert (an escaped or verbatim macro) stay as they are: no converted match is found for them.
     */
    fun parts(html: String, source: String?, stem: String?): List<HtmlFragment.Part> {
        val parts = HtmlFragment.parse(html, allowRemoteImages = true).toMutableList()
        val macros = macros(source.orEmpty(), stem)
        if (macros.isEmpty()) return parts
        var cursor = 0
        for (macro in macros) {
            val open = if (macro.latex) "\\(" else "\\$"
            val close = if (macro.latex) "\\)" else "\\$"
            val found = find(parts, open, close, cursor) ?: continue
            val inner = found.text.substring(found.start + open.length, found.end)
            val replacement = if (macro.latex) {
                listOf(
                    HtmlFragment.Markup("<span class=\"mdr-math mdr-math-inline\">", "span", false),
                    HtmlFragment.TextRun(inner),
                    HtmlFragment.Markup("</span>", "span", true),
                )
            } else {
                listOf(
                    HtmlFragment.Markup(
                        "<span class=\"mdr-math mdr-math-inline mdr-math-failed\" title=\"$ASCIIMATH_UNSUPPORTED\">",
                        "span",
                        false,
                    ),
                    HtmlFragment.TextRun("\$" + inner + "\$"),
                    HtmlFragment.Markup("</span>", "span", true),
                )
            }
            val before = found.text.substring(0, found.start)
            val after = found.text.substring(found.end + close.length)
            parts.removeAt(found.index)
            val inserted = mutableListOf<HtmlFragment.Part>()
            if (before.isNotEmpty()) inserted += HtmlFragment.TextRun(before)
            inserted += replacement
            if (after.isNotEmpty()) inserted += HtmlFragment.TextRun(after)
            parts.addAll(found.index, inserted)
            cursor = found.offset + found.start + inner.length + if (macro.latex) 0 else 2
        }
        return parts
    }

    /** The types of the inline math macros in [source], in order. */
    private fun macros(source: String, stem: String?): List<Macro> {
        val result = mutableListOf<Macro>()
        var from = 0
        while (from < source.length) {
            val match = MACRO.find(source, from) ?: break
            val name = match.groupValues[1]
            var i = match.range.last + 1
            var closed = false
            while (i < source.length) {
                val c = source[i]
                if (c == '\\' && i + 1 < source.length) {
                    i += 2
                    continue
                }
                if (c == ']') {
                    closed = true
                    break
                }
                i++
            }
            if (!closed) {
                from = match.range.last + 1
                continue
            }
            val latex = when (name) {
                LATEX -> true
                ASCIIMATH -> false
                else -> isLatexStem(stem)
            }
            result += Macro(latex)
            from = i + 1
        }
        return result
    }

    private class Macro(val latex: Boolean)

    private class Found(
        val index: Int,
        /** Offset of the TextRun in the whole visible text. */
        val offset: Int,
        val text: String,
        val start: Int,
        val end: Int,
    )

    /**
     * The first [open]..[close] pair at or after [from], inside one text run. The converted formula is a text run:
     * Asciidoctor escapes the TeX as HTML text, so no markup parts split it.
     */
    private fun find(parts: List<HtmlFragment.Part>, open: String, close: String, from: Int): Found? {
        var offset = 0
        for ((index, part) in parts.withIndex()) {
            if (part !is HtmlFragment.TextRun) continue
            val localFrom = (from - offset).coerceAtLeast(0)
            val start = part.text.indexOf(open, localFrom)
            if (start >= 0) {
                val end = part.text.indexOf(close, start + open.length)
                if (end >= 0) return Found(index, offset, part.text, start, end)
            }
            offset += part.text.length
        }
        return null
    }

    private val MACRO = Regex("""(?<!\\)(latexmath|asciimath|stem):\[""")
}
