# ADR 0008: Draw AsciiMath by converting it to LaTeX for KaTeX

Status: proposed, phase 4 (P4.9).

## Context

Asciidoctor has two math notations: LaTeX (`latexmath`) and AsciiMath (`asciimath`). AsciiMath is Asciidoctor's
default: `:stem:` without a value, `stem:[...]` and `[stem]` blocks all mean AsciiMath unless the document sets
`:stem: latexmath`. P4.7 draws only LaTeX, with the bundled KaTeX, and leaves AsciiMath as monospace source marked
"AsciiMath is not supported". So the most common way of writing math in AsciiDoc is the one that is not drawn.

KaTeX reads only LaTeX. Whatever draws AsciiMath has to:

1. stay offline and inside the page's CSP (NFR-8, NFR-13): no network, no new script origin;
2. keep the plugin zip within NFR-14 (at most 1 MB more than 1.3.0, together with the rest of phase 4);
3. keep the diff behavior of P4.7: a formula is one atom of the word diff, a changed block formula has a source
   diff, and `Render math` off shows source;
4. leave Markdown unchanged.

## Options considered

- **Convert AsciiMath to LaTeX in the engine, in Kotlin** (chosen): a small converter in `pl.mdreview.engine.asciidoc`
  turns each AsciiMath formula into LaTeX before the block is rendered, and the existing KaTeX drawing does the
  rest. No new library and no change to the page script, CSP or export. The converter is unit-tested on the JVM
  with the rest of the front end (NFR-15). Cost: a second implementation of AsciiMath's grammar, which can differ
  from the reference `ASCIIMathML.js` on edge cases.
- **Bundle a JavaScript AsciiMath-to-LaTeX converter and run it in the page before KaTeX**: reuses an existing
  implementation, but adds a third-party script to every page with formulas, a license and notice to review, and
  page-side logic the engine tests cannot reach. Export works only because the page is drawn before the snapshot.
- **MathJax with its AsciiMath input**: the reference renderer for Asciidoctor's HTML output, but several MB, a
  second math engine next to KaTeX, and different output from Markdown formulas.
- **Keep AsciiMath as source** (the P4.7 state): no cost, but the default notation stays undrawn.

## Decision

- `AsciiMathToTex.convert(source): String?` in `pl.mdreview.engine.asciidoc` converts one formula. It covers the
  symbol tables of the AsciiMath reference (Greek letters, operators, relations, arrows, logic, functions,
  accents, font commands), fractions with `/`, sub- and superscripts, grouping brackets including invisible
  `{: :}`, matrices written as nested brackets, `text(...)` and quoted text. It returns `null` for input it cannot
  parse.
- `AsciiDocMath` and `AsciiDocBlockSource.mathBlock` call it for `asciimath:[...]`, AsciiMath `stem:[...]` and
  `[asciimath]` / AsciiMath `[stem]` blocks. The `.mdr-math` element carries the converted LaTeX for KaTeX, and the
  original AsciiMath in a `data-source` attribute, which the page restores as text when KaTeX fails, so the reviewer
  sees what they wrote rather than the conversion.
- `plainText`, keys and the block's source diff use the original AsciiMath, so a formula that converts to the same
  LaTeX but is written differently is still a change, and the source diff shows AsciiMath lines.
- A formula the converter rejects keeps today's behavior: monospace source, `mdr-math-failed`, with the note
  "AsciiMath formula could not be converted" instead of "AsciiMath is not supported".
- Markdown `$...$` formulas never go through the converter.

## Consequences

- The default AsciiDoc math notation is drawn the same way as LaTeX and Markdown formulas, offline, with no new
  bundled library; the zip grows only by the converter's classes.
- The converter is ours to maintain. P4.9 tests it against the examples of the AsciiMath reference, and each
  difference a user reports gets a test case.
- KaTeX errors on converted output show the AsciiMath source and KaTeX's message, as for LaTeX formulas today.
- Nothing changes when `Render math` is off.
