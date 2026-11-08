# 67-adoc-asciimath

AsciiMath through `stem:[...]` with an unset `:stem:`, `asciimath:[...]`, `[stem]` and `[asciimath]` blocks (P4.9).
Everything is converted to LaTeX for the bundled KaTeX; the original AsciiMath stays in `data-source` and in the
block's plain text.

- Paragraph "Inline ...": modified. `a/b` became `frac(a)(b)`, so the whole AsciiMath formula is removed and added as
  one atom even though both spellings convert to the same LaTeX. `sqrt(x)` and the LaTeX `x^2` are equal atoms.
- `[stem]` block `[[a,b],[c,d]]` -> `[[a,b],[c,e]]`: modified MATH block. Both matrices are converted to bracketed
  matrices; the changed block shows the old and new drawings and the source line diff.
- `[asciimath]` block `sum_(i=1)^n i`: equal, drawn by KaTeX.
- `[stem]` block `((1),(2))`: equal, a bracketed column vector drawn by KaTeX.
- Paragraph "A formula the converter cannot read": equal. `asciimath:[a + (b]` stays source, marked
  `mdr-math-failed` with the note "AsciiMath formula could not be converted"; it is not an atom.
- With "Render math" off, every formula stays monospace source; the diff structure and source toggles are unchanged.
