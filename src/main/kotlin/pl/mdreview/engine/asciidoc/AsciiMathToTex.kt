package pl.mdreview.engine.asciidoc

/**
 * Converts the AsciiMath formulas Asciidoctor passes through into LaTeX for the bundled KaTeX (P4.9, ADR 0008).
 *
 * This is deliberately a small, dependency-free subset of the AsciiMath reference: constants and Greek letters,
 * operators, relations, arrows, logic, standard functions, accents, font commands, the explicit layout commands,
 * fractions, scripts, grouping and bracketed matrices. Unknown words stay letters; input that cannot be parsed
 * (unbalanced brackets, a dangling `/`, `_` or `^`, an empty formula) returns `null`, so the caller can keep the
 * source visible.
 */
internal object AsciiMathToTex {

    fun convert(source: String): String? {
        val tokens = tokenize(source) ?: return null
        if (tokens.isEmpty()) return null
        return try {
            val tex = Parser(tokens).parseAll()
            tex.takeIf { it.isNotBlank() }
        } catch (_: ParseFailure) {
            null
        }
    }

    private class ParseFailure : RuntimeException()

    private data class Token(val text: String, val quoted: Boolean = false)

    private class Parser(private val tokens: List<Token>) {
        private var position = 0

        fun parseAll(): String {
            val result = sequence(emptySet())
            if (position != tokens.size) fail()
            return result
        }

        private fun sequence(stop: Set<String>): String {
            val parts = mutableListOf<String>()
            while (position < tokens.size && tokens[position].text !in stop) {
                parts += postfix()
            }
            return parts.joinToString(" ")
        }

        /** One atom with its `/`, `_`, `^` and prime postfixes. */
        private fun postfix(): String {
            var node = atom()
            while (position < tokens.size) {
                when (val next = tokens[position].text) {
                    "/" -> {
                        position++
                        node = "\\frac{${stripOuter(node)}}{${stripOuter(scriptedAtom())}}"
                    }
                    "_" -> {
                        position++
                        node = "${node}_{${script()}}"
                    }
                    "^" -> {
                        position++
                        node = "${node}^{${script()}}"
                    }
                    "'" -> {
                        position++
                        node += "'"
                    }
                    else -> break
                }
            }
            return node
        }

        /** An atom with `_` and `^` scripts but without a following fraction; the denominator of `/`. */
        private fun scriptedAtom(): String {
            var node = atom()
            while (position < tokens.size) {
                when (tokens[position].text) {
                    "_" -> {
                        position++
                        node = "${node}_{${script()}}"
                    }
                    "^" -> {
                        position++
                        node = "${node}^{${script()}}"
                    }
                    "'" -> {
                        position++
                        node += "'"
                    }
                    else -> break
                }
            }
            return node
        }

        /** A script argument: brackets are grouping, not visible delimiters. */
        private fun script(): String = when (current()) {
            "(", "[", "{", "{:", "(:" -> {
                val close = closeFor(take())
                val body = sequence(setOf(close))
                expect(close)
                body
            }
            else -> atom()
        }

        private fun atom(): String {
            if (position >= tokens.size) fail()
            val token = tokens[position++]
            if (token.quoted) return "\\text{${textEscape(token.text)}}"
            return when (val text = token.text) {
                "(" -> delimitedOrMatrix("(", ")", "pmatrix", "\\left(", "\\right)")
                "[" -> delimitedOrMatrix("[", "]", "bmatrix", "\\left[", "\\right]")
                "{" -> group("}", "", "")
                "{:" -> group(":}", "", "")
                "(:" -> group(":)", "", "")
                "<<" -> group(">>", "\\left\\langle ", "\\right\\rangle")
                "|" -> group("|", "\\left|", "\\right|")
                "text", "mbox" -> "\\text{${textEscape(rawArgument())}}"
                "sqrt" -> "\\sqrt{${argument()}}"
                "root" -> {
                    val index = argument()
                    "\\sqrt[$index]{${argument()}}"
                }
                "frac" -> "\\frac{${argument()}}{${argument()}}"
                "stackrel" -> "\\stackrel{${argument()}}{${argument()}}"
                "overset" -> "\\overset{${argument()}}{${argument()}}"
                "underset" -> "\\underset{${argument()}}{${argument()}}"
                "color" -> {
                    val color = rawArgument()
                    "\\textcolor{$color}{${argument()}}"
                }
                "cancel", "bcancel", "xcancel" -> "\\$text{${argument()}}"
                "abs" -> "\\left|${argument()}\\right|"
                "floor" -> "\\left\\lfloor ${argument()}\\right\\rfloor"
                "ceil" -> "\\left\\lceil ${argument()}\\right\\rceil"
                "norm" -> "\\left\\|${argument()}\\right\\|"
                in ACCENTS -> "\\${ACCENTS[text]}{${argument()}}"
                in FONTS -> "\\${FONTS[text]}{${argument()}}"
                in FUNCTIONS -> FUNCTIONS[text]!!
                in SYMBOLS -> SYMBOLS[text]!!
                "/" -> fail()
                "_", "^" -> fail()
                ")", "]", "}", ":)", ":}", ">>" -> fail()
                else -> when {
                    NUMBER.matches(text) -> text
                    LETTERS.matches(text) -> text
                    text.length == 1 -> SYMBOLS[text] ?: text
                    else -> text
                }
            }
        }

        private fun group(close: String, openTex: String, closeTex: String): String {
            val body = sequence(setOf(close))
            expect(close)
            return openTex + body + closeTex
        }

        /** A bracketed group, or a matrix/vector when it contains bracketed rows. */
        private fun delimitedOrMatrix(
            open: String,
            close: String,
            environment: String,
            openTex: String,
            closeTex: String,
        ): String {
            val end = matchingClose(position - 1, open, close) ?: fail()
            val inner = tokens.subList(position, end)
            matrix(inner, open, close, environment)?.let {
                position = end + 1
                return it
            }
            val body = sequence(setOf(close))
            expect(close)
            return openTex + body + closeTex
        }

        /** Matching close index in [tokens] for [open] at [start]. */
        private fun matchingClose(start: Int, open: String, close: String): Int? {
            var depth = 0
            for (i in start until tokens.size) {
                when (tokens[i].text) {
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            return null
        }

        private fun matrix(inner: List<Token>, open: String, close: String, environment: String): String? {
            val rows = splitTopLevel(inner, ",")
            if (rows.isEmpty() || rows.any { it.isEmpty() }) return null
            if (rows.any { it.first().text != open || matchingCloseIn(it, 0, open, close) != it.size - 1 }) return null
            val cells = rows.map { row ->
                val rowInner = row.subList(1, row.size - 1)
                val split = splitTopLevel(rowInner, ",")
                if (split.any { it.isEmpty() }) return null
                split.map { Parser(it).parseAll() }
            }
            val body = cells.joinToString(" \\\\ ") { row -> row.joinToString(" & ") }
            return "\\begin{$environment}$body\\end{$environment}"
        }

        private fun argument(): String = when (current()) {
            "(", "[", "{", "{:", "(:" -> {
                val close = closeFor(take())
                val body = sequence(setOf(close))
                expect(close)
                body
            }
            else -> atom()
        }

        private fun rawArgument(): String = when (val open = current()) {
            "(", "{", "[", "{:", "(:" -> {
                take()
                val close = closeFor(open)
                val start = position
                val end = matchingClose(position - 1, open, close) ?: fail()
                val body = tokens.subList(start, end).joinToString(" ") { it.text }
                position = end + 1
                body
            }
            else -> atom()
        }

        private fun current(): String = tokens.getOrNull(position)?.text ?: fail()

        private fun take(): String = tokens.getOrNull(position)?.text?.also { position++ } ?: fail()

        private fun expect(value: String) {
            if (take() != value) fail()
        }

        private fun fail(): Nothing = throw ParseFailure()
    }

    /** Matching close index in [tokens] for the opener at [start]. */
    private fun matchingCloseIn(tokens: List<Token>, start: Int, open: String, close: String): Int? {
        var depth = 0
        for (i in start until tokens.size) {
            when (tokens[i].text) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun splitTopLevel(tokens: List<Token>, separator: String): List<List<Token>> {
        val result = mutableListOf<List<Token>>()
        var start = 0
        var depth = 0
        for ((i, token) in tokens.withIndex()) {
            when (token.text) {
                "(", "[", "{", "{:", "(:" -> depth++
                ")", "]", "}", ":)", ":}" -> depth--
                separator -> if (depth == 0) {
                    result += tokens.subList(start, i)
                    start = i + 1
                }
            }
        }
        result += tokens.subList(start, tokens.size)
        return result
    }

    private fun closeFor(open: String): String = when (open) {
        "(" -> ")"
        "[" -> "]"
        "{" -> "}"
        "{:" -> ":}"
        "(:" -> ":)"
        else -> throw ParseFailure()
    }

    /** Remove one pair of visible grouping delimiters around a fraction component. */
    private fun stripOuter(tex: String): String {
        for ((open, close) in listOf(
            "\\left(" to "\\right)",
            "\\left[" to "\\right]",
            "\\left\\langle " to "\\right\\rangle",
        )) {
            if (tex.startsWith(open) && tex.endsWith(close)) return tex.substring(open.length, tex.length - close.length)
        }
        return tex
    }

    private fun textEscape(text: String): String = buildString {
        for (c in text) {
            when (c) {
                '\\', '{', '}', '_', '^', '&', '%', '$', '#', '~' -> append('\\').append(c)
                else -> append(c)
            }
        }
    }

    private fun tokenize(source: String): List<Token>? {
        val result = mutableListOf<Token>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            if (c == '"') {
                val end = source.indexOf('"', i + 1)
                if (end < 0) return null
                result += Token(source.substring(i + 1, end), quoted = true)
                i = end + 1
                continue
            }
            if (c.isDigit()) {
                var end = i + 1
                while (end < source.length && (source[end].isDigit() || source[end] == '.')) end++
                result += Token(source.substring(i, end))
                i = end
                continue
            }
            if (c.isLetter()) {
                var end = i + 1
                while (end < source.length && source[end].isLetter()) end++
                result += Token(source.substring(i, end))
                i = end
                continue
            }
            val multi = MULTI.firstOrNull { source.startsWith(it, i) }
            if (multi != null) {
                result += Token(multi)
                i += multi.length
                continue
            }
            result += Token(c.toString())
            i++
        }
        return result
    }

    private val NUMBER = Regex("""\d+(\.\d+)?""")
    private val LETTERS = Regex("""[A-Za-z]+""")

    private val MULTI = listOf(
        "<=>", "|->", "->>", ">->", "|--", "|==", "_|_", "!in", "...",
        "~~", "~=", "-=", "+-", "-+", "**", "//", "o+", "ox", "o.",
        "->", "<-", "=>", "<=", ">=", "!=", "<<", ">>", "{:", ":}", "(:", ":)",
    )

    private val ACCENTS = mapOf(
        "hat" to "hat", "bar" to "bar", "vec" to "vec", "dot" to "dot", "ddot" to "ddot",
        "ul" to "underline", "obrace" to "overbrace", "ubrace" to "underbrace",
        "tilde" to "tilde", "widehat" to "widehat", "widetilde" to "widetilde",
    )

    private val FONTS = mapOf(
        "bb" to "mathbb", "bbb" to "mathbf", "cc" to "mathcal",
        "tt" to "mathtt", "fr" to "mathfrak", "sf" to "mathsf",
    )

    private val FUNCTIONS = mapOf(
        "sin" to "\\sin", "cos" to "\\cos", "tan" to "\\tan", "cot" to "\\cot",
        "sec" to "\\sec", "csc" to "\\csc", "sinh" to "\\sinh", "cosh" to "\\cosh",
        "tanh" to "\\tanh", "coth" to "\\coth", "arcsin" to "\\arcsin", "arccos" to "\\arccos",
        "arctan" to "\\arctan", "log" to "\\log", "ln" to "\\ln", "lg" to "\\lg",
        "exp" to "\\exp", "lim" to "\\lim", "liminf" to "\\liminf", "limsup" to "\\limsup",
        "max" to "\\max", "min" to "\\min",
        "det" to "\\det", "dim" to "\\dim", "gcd" to "\\gcd", "hom" to "\\hom",
        "ker" to "\\ker", "Pr" to "\\Pr", "arg" to "\\arg", "deg" to "\\deg",
        "mod" to "\\operatorname{mod}", "sum" to "\\sum", "prod" to "\\prod",
        "int" to "\\int", "oint" to "\\oint", "iint" to "\\iint", "iiint" to "\\iiint",
    )

    private val GREEK = mapOf(
        "alpha" to "\\alpha", "beta" to "\\beta", "gamma" to "\\gamma", "delta" to "\\delta",
        "epsilon" to "\\epsilon", "varepsilon" to "\\varepsilon", "zeta" to "\\zeta",
        "eta" to "\\eta", "theta" to "\\theta", "vartheta" to "\\vartheta", "iota" to "\\iota",
        "kappa" to "\\kappa", "lambda" to "\\lambda", "mu" to "\\mu", "nu" to "\\nu",
        "xi" to "\\xi", "omicron" to "o", "pi" to "\\pi", "varpi" to "\\varpi",
        "rho" to "\\rho", "varrho" to "\\varrho", "sigma" to "\\sigma", "varsigma" to "\\varsigma",
        "tau" to "\\tau", "upsilon" to "\\upsilon", "phi" to "\\phi", "varphi" to "\\varphi",
        "chi" to "\\chi", "psi" to "\\psi", "omega" to "\\omega",
        "Gamma" to "\\Gamma", "Delta" to "\\Delta", "Theta" to "\\Theta", "Lambda" to "\\Lambda",
        "Xi" to "\\Xi", "Pi" to "\\Pi", "Sigma" to "\\Sigma", "Upsilon" to "\\Upsilon",
        "Phi" to "\\Phi", "Psi" to "\\Psi", "Omega" to "\\Omega",
    )

    private val SYMBOLS: Map<String, String> = GREEK + mapOf(
        "oo" to "\\infty", "del" to "\\partial", "grad" to "\\nabla",
        "RR" to "\\mathbb{R}", "NN" to "\\mathbb{N}", "ZZ" to "\\mathbb{Z}",
        "QQ" to "\\mathbb{Q}", "CC" to "\\mathbb{C}",
        "and" to "\\wedge", "or" to "\\vee", "not" to "\\neg",
        "AA" to "\\forall", "EE" to "\\exists", "TT" to "\\top", "_|_" to "\\bot",
        "in" to "\\in", "!in" to "\\notin", "sub" to "\\subset", "sup" to "\\supset",
        "sube" to "\\subseteq", "supe" to "\\supseteq", "nn" to "\\cap", "uu" to "\\cup",
        "prop" to "\\propto", "aleph" to "\\aleph", "therefore" to "\\therefore",
        "because" to "\\because", "ldots" to "\\ldots", "cdots" to "\\cdots",
        "vdots" to "\\vdots", "ddots" to "\\ddots",
        "+" to "+", "-" to "-", "*" to "\\cdot", "**" to "\\ast", "//" to "/",
        "=" to "=", "<" to "<", ">" to ">", "<=" to "\\le", ">=" to "\\ge",
        "!=" to "\\ne", "~=" to "\\cong", "~~" to "\\approx", "-=" to "\\equiv",
        "xx" to "\\times", "-:" to "\\div", "+-" to "\\pm", "-+" to "\\mp",
        "@" to "\\circ", "o+" to "\\oplus", "ox" to "\\otimes", "o." to "\\odot",
        "->" to "\\to", "<-" to "\\leftarrow", "<->" to "\\leftrightarrow",
        "=>" to "\\Rightarrow", "<=>" to "\\Leftrightarrow", "|->" to "\\mapsto",
        "->>" to "\\twoheadrightarrow", ">->" to "\\rightarrowtail", "~>" to "\\leadsto",
        "|--" to "\\vdash", "|==" to "\\models", "..." to "\\ldots",
        "," to ",", ";" to ";", ":" to ":", "." to ".", "!" to "!", "?" to "?",
    )
}
