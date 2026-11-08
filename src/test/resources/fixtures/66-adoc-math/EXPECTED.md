# 66-adoc-math

Inline (`latexmath:[...]`, `stem:[...]` with `:stem: latexmath`) and block (`[latexmath]`, `[stem]`) formulas
(P4.7). A drawn formula is one unit: the word diff never splits it.

- Paragraph "The area of a circle": modified. `latexmath:[A = \pi r^2]` is equal; `stem:[C = 2 \pi r]` is removed
  and `stem:[C = \tau r]` added, each as one inline segment.
- Block formula `E = mc^2`: equal, a `MATH` block.
- Block formula of the quadratic formula: modified (`2a` became `2 a`). Its source is the whole formula, and the
  changed formula has a "Show source changes" toggle, like a changed Mermaid diagram.
- Paragraph "An AsciiMath formula, not drawn": equal. The inline AsciiMath is not an atom and is not drawn.
- AsciiMath block: equal, a `MATH` block that the renderer marks `mdr-math-failed` with the note "AsciiMath is not
  supported"; it stays monospace source and is compared as text.
- Paragraph "Euler's identity:" and the block formula `e^{i\pi} + 1 = 0`: added.
- The Pythagorean block formula `a^2 + b^2 = c^2`: removed.
- Paragraph "An unfinished formula": added. `latexmath:[\frac{1}{]` is one inline formula; KaTeX cannot parse it, so
  the page shows its source with an error marker and the note, as for Markdown fixture 40.

With "Render math" off the page does not load KaTeX; the formulas stay monospace source in their `.mdr-math`
elements, while the diff structure and the source-diff toggles are the same.
