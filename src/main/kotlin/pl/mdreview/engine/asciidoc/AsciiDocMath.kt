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
 * LaTeX is drawn by KaTeX directly. AsciiMath is converted to LaTeX by [AsciiMathToTex] (P4.9): the element carries
 * the converted LaTeX in `data-tex` and the original AsciiMath in `data-source`, while its text and the block's plain
 * text stay the AsciiMath source so the word diff and keys compare what was written. Input the converter rejects stays
 * source, marked `.mdr-math-failed` with [ASCIIMATH_CONVERSION_FAILED].
 */
internal object AsciiDocMath {

    const val LATEX = "latexmath"
    const val ASCIIMATH = "asciimath"
    const val ASCIIMATH_CONVERSION_FAILED = "AsciiMath formula could not be converted"

    /** Block style drawn by KaTeX: `[latexmath]`, or `[stem]` when the document's `stem` is `latexmath`. */
    fun isLatexStyle(style: String?): Boolean = style?.equals(LATEX, ignoreCase = true) == true

    /** `stem:[...]` is LaTeX only when the document's `stem` attribute is `latexmath`; Asciidoctor's default is AsciiMath. */
    fun isLatexStem(stem: String?): Boolean = stem?.equals(LATEX, ignoreCase = true) == true

    /**
     * [html] with each formula found in [source] replaced by a `.mdr-math` element. Formulas that Asciidoctor did not
     * convert (an escaped or verbatim macro) stay as they are: no converted match is found for them.
     */
    fun parts(
        html: String,
        source: String?,
        stem: String?,
        footnoteLabels: Map<Int, String> = emptyMap(),
    ): List<HtmlFragment.Part> {
        val parsed = HtmlFragment.parse(html, allowRemoteImages = true)
        val parts = if (footnoteLabels.isEmpty()) parsed.toMutableList() else withFootnoteLabels(parsed, footnoteLabels).toMutableList()
        val macros = macros(source.orEmpty(), stem)
        if (macros.isEmpty()) return parts
        var cursor = 0
        for (macro in macros) {
            val latex = macro.notation == Notation.LATEX
            val open = if (latex) "\\(" else "\\$"
            val close = if (latex) "\\)" else "\\$"
            val found = find(parts, open, close, cursor) ?: continue
            val replacement = when {
                latex -> listOf(
                    HtmlFragment.Markup("<span class=\"mdr-math mdr-math-inline\">", "span", false),
                    HtmlFragment.TextRun(macro.source),
                    HtmlFragment.Markup("</span>", "span", true),
                )
                macro.tex != null -> listOf(
                    HtmlFragment.Markup(
                        "<span class=\"mdr-math mdr-math-inline\" data-tex=\"" + attribute(macro.tex) +
                            "\" data-source=\"" + attribute(macro.source) + "\">",
                        "span",
                        false,
                    ),
                    HtmlFragment.TextRun(macro.source),
                    HtmlFragment.Markup("</span>", "span", true),
                )
                else -> listOf(
                    HtmlFragment.Markup(
                        "<span class=\"mdr-math mdr-math-inline mdr-math-failed\" title=\"$ASCIIMATH_CONVERSION_FAILED\">",
                        "span",
                        false,
                    ),
                    HtmlFragment.TextRun(macro.source),
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
            cursor = found.offset + found.start + macro.source.length
        }
        return parts
    }

    /** The types and sources of the inline math macros in [source], in order. */
    private fun macros(source: String, stem: String?): List<Macro> {
        val result = mutableListOf<Macro>()
        var from = 0
        while (from < source.length) {
            val match = MACRO.find(source, from) ?: break
            val name = match.groupValues[1]
            var i = match.range.last + 1
            var closed = false
            val tex = StringBuilder()
            while (i < source.length) {
                val c = source[i]
                if (c == '\\' && i + 1 < source.length) {
                    // `\]` is an escaped bracket in the macro; other backslashes are TeX.
                    if (source[i + 1] == ']') tex.append(']') else tex.append(c).append(source[i + 1])
                    i += 2
                    continue
                }
                if (c == ']') {
                    closed = true
                    break
                }
                tex.append(c)
                i++
            }
            if (!closed) {
                from = match.range.last + 1
                continue
            }
            val notation = when (name) {
                LATEX -> Notation.LATEX
                ASCIIMATH -> Notation.ASCIIMATH
                else -> if (isLatexStem(stem)) Notation.LATEX else Notation.ASCIIMATH
            }
            val source = tex.toString()
            result += Macro(notation, source, if (notation == Notation.ASCIIMATH) AsciiMathToTex.convert(source) else source)
            from = i + 1
        }
        return result
    }

    private enum class Notation { LATEX, ASCIIMATH }

    private class Macro(val notation: Notation, val source: String, val tex: String?)

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

    /**
     * A footnote reference still carries Asciidoctor's numeric href. Put the stable label chosen by the front end on
     * the link so the renderer can point it at the matching FOOTNOTE block after renumbering.
     */
    fun withFootnoteLabels(parts: List<HtmlFragment.Part>, labels: Map<Int, String>): List<HtmlFragment.Part> {
        if (labels.isEmpty()) return parts
        return parts.mapIndexed { i, part ->
            if (part !is HtmlFragment.Markup || part.closing || part.tag != "a") return@mapIndexed part
            val href = HtmlFragment.attribute(part, "href") ?: return@mapIndexed part
            val index = FOOTNOTE_DEF.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapIndexed part
            labels[index]?.let { HtmlFragment.withAttribute(part, "data-footnote-label", it) } ?: part
        }
    }

    private fun attribute(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '<' -> append("&lt;")
                else -> append(c)
            }
        }
    }

    private val MACRO = Regex("""(?<!\\)(latexmath|asciimath|stem):\[""")
    private val FOOTNOTE_DEF = Regex("""#_footnotedef_(\d+)""")
}
