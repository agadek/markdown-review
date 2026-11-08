package pl.mdreview.engine.asciidoc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AsciiMathToTexTest {

    @Test
    fun `fractions scripts groups and layout commands`() {
        assertEquals("\\frac{a}{b}", AsciiMathToTex.convert("a/b"))
        assertEquals("\\frac{a}{b^{2}}", AsciiMathToTex.convert("a/b^2"))
        assertEquals("\\sum_{i = 1}^{n} i", AsciiMathToTex.convert("sum_(i=1)^n i"))
        assertEquals("\\sqrt{x}", AsciiMathToTex.convert("sqrt(x)"))
        assertEquals("\\sqrt[3]{x}", AsciiMathToTex.convert("root(3)(x)"))
        assertEquals("\\frac{a}{b}", AsciiMathToTex.convert("frac(a)(b)"))
        assertEquals("\\hat{x}", AsciiMathToTex.convert("hat x"))
        assertEquals("\\left(a + b\\right)", AsciiMathToTex.convert("(a+b)"))
        assertEquals("\\left|x\\right|", AsciiMathToTex.convert("abs(x)"))
        assertEquals("\\textcolor{red}{x}", AsciiMathToTex.convert("color(red)(x)"))
    }

    @Test
    fun `symbols functions and fonts`() {
        assertEquals("\\alpha + \\beta", AsciiMathToTex.convert("alpha + beta"))
        assertEquals("\\sin x", AsciiMathToTex.convert("sin x"))
        assertEquals("\\mathbb{R}", AsciiMathToTex.convert("bb R"))
        assertEquals("\\infty", AsciiMathToTex.convert("oo"))
        assertEquals("a \\to b", AsciiMathToTex.convert("a -> b"))
        assertEquals("\\text{hello world}", AsciiMathToTex.convert("text(hello world)"))
        assertEquals("\\text{a + b}", AsciiMathToTex.convert("\"a + b\""))
    }

    @Test
    fun `bracketed matrices and vectors`() {
        assertEquals(
            "\\begin{bmatrix}a & b \\\\ c & d\\end{bmatrix}",
            AsciiMathToTex.convert("[[a,b],[c,d]]"),
        )
        assertEquals(
            "\\begin{pmatrix}1 \\\\ 2\\end{pmatrix}",
            AsciiMathToTex.convert("((1),(2))"),
        )
        assertEquals("\\left[a , b\\right]", AsciiMathToTex.convert("[a,b]"))
    }

    @Test
    fun `relations accents fonts and explicit commands`() {
        assertEquals("a \\le b", AsciiMathToTex.convert("a <= b"))
        assertEquals("a \\to b", AsciiMathToTex.convert("a -> b"))
        assertEquals("\\forall x \\in \\mathbb{R}", AsciiMathToTex.convert("AA x in RR"))
        assertEquals("\\hat{x}", AsciiMathToTex.convert("hat(x)"))
        assertEquals("\\bar{x}", AsciiMathToTex.convert("bar x"))
        assertEquals("\\vec{F}", AsciiMathToTex.convert("vec F"))
        assertEquals("\\mathbf{A}", AsciiMathToTex.convert("bbb A"))
        assertEquals("\\cancel{x}", AsciiMathToTex.convert("cancel(x)"))
        assertEquals("\\left\\lfloor x\\right\\rfloor", AsciiMathToTex.convert("floor(x)"))
        assertEquals("\\left\\|x\\right\\|", AsciiMathToTex.convert("norm(x)"))
        assertEquals("\\stackrel{?}{=}", AsciiMathToTex.convert("stackrel(?)(=)"))
        assertEquals("a + b", AsciiMathToTex.convert("{:a+b:}"))
        assertEquals("\\left\\langle a , b\\right\\rangle", AsciiMathToTex.convert("<<a,b>>"))
    }

    @Test
    fun `unparseable input returns null`() {
        assertNull(AsciiMathToTex.convert(""))
        assertNull(AsciiMathToTex.convert("a + (b"))
        assertNull(AsciiMathToTex.convert("a/"))
        assertNull(AsciiMathToTex.convert("a_"))
        assertNull(AsciiMathToTex.convert("a^"))
        assertNull(AsciiMathToTex.convert("a]"))
    }
}
