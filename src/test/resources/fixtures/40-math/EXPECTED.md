# 40-math

Inline (`$...$`) and block (`$$...$$`) formulas (P2.4 wave B, drawn in P2.8). A formula is a node of its own,
compared as one unit: the word diff never splits it.

- Paragraph "The area of a circle": modified. `$A = \pi r^2$` is equal; `$C = 2 \pi r$` is removed and
  `$C = \tau r$` added, each as one inline segment.
- Block formula `E = mc^2`: equal, a `MATH` block.
- Block formula of the quadratic formula: modified (`2a` became `2 a`). Its source is the whole formula, and the
  changed formula has a "Show source changes" toggle, like a changed Mermaid diagram.
- Paragraph "Euler's identity:" and the block formula `e^{i\pi} + 1 = 0`: added.
- Paragraph "An unfinished formula": added. `$\frac{1}{$` is still an inline formula node; KaTeX cannot parse it,
  so the page shows its source with an error marker (P2.8).
- `` `$x$` `` in a code span stays code, not a formula: equal.
- "Plans cost $5 a month, or $10": prices, not a formula (a closing `$` followed by a digit does not close a
  formula, as on GitHub). Modified with a plain word diff: `$10` removed, `$12` added.

Rendering (P2.4): formulas are monospace source in inert elements, inline `<span class="mdr-math mdr-math-inline">`
and block `<div class="mdr-math mdr-math-display">`, with no scripts in `bodyHtml`. Drawing (P2.8): the page loads
the bundled KaTeX under its CSP nonce only when the diff has a formula.
