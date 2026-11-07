# ADR 0006: Reuse the page, styles and export for AsciiDoc

Status: proposed, phase 4.

## Context

Everything after `render()` is shared infrastructure that took phases 2 and 3 to build: `HtmlPage` (theme
variables, CSP with a nonce, the page script for navigation, block actions, editing and the block menu), `diff.css`
and its `mdr-*` class contract (`CLASSES.md`), the bundled Mermaid and KaTeX with their drawing scripts, the custom
stylesheet, and HTML, PDF and multi-file export, which read the page as drawn.

Asciidoctor can produce a complete HTML5 document with its own stylesheet, or HTML for one node with `convert()`.
Its block HTML wraps everything in `div.paragraph`, `div.listingblock`, `div.admonitionblock > table`, and so on,
and its stylesheet sets its own fonts and colors.

## Options considered

- **Asciidoctor's HTML and stylesheet per block**: looks like the default Asciidoctor output, but its CSS fights the
  editor theme (NFR-5) and the custom stylesheet, its table-based admonitions do not fit the side-by-side grid, and
  block `convert()` output would have to be cut apart to put word marks and `data-side` / `data-line` in place.
- **Asciidoctor's block HTML with our CSS** for its classes: fewer surprises for AsciiDoc users who style
  Asciidoctor output, but two sets of block markup to keep in sync in `diff.css`, and the same cutting-apart.
- **Our block markup, Asciidoctor's inline HTML** (chosen): the block element is written by `DiffHtmlRenderer` as for
  Markdown (`h1`–`h6`, `p`, `li`, `tr`, `pre`, the alert markup); only the content inside it, which `getContent()` /
  `getText()` / `getTitle()` return, comes from Asciidoctor.

## Decision

- Block markup comes from `DiffHtmlRenderer` and uses the existing `mdr-*` classes. AsciiDoc-only structures get new
  classes, added to `CLASSES.md` and styled in `diff.css` from the existing CSS variables only:
  `mdr-container` with `mdr-container-example|sidebar|quote|verse|open|admonition`, `mdr-block-title`,
  `mdr-dlist`, `mdr-callout`, `mdr-include` and `mdr-attribute`. No new CSS variable, no `prefers-color-scheme`.
- Admonitions use the GitHub alert markup and styles (`NOTE`, `TIP`, `IMPORTANT`, `WARNING`, `CAUTION` are the same
  five types), so their colors follow the color scheme settings already in place.
- Inline HTML from Asciidoctor goes through `HtmlFragment.parse` (the same allowlist as Markdown, NFR-8) and is
  stored as parts. Asciidoctor's inline classes that carry meaning are mapped to our styles: `<mark>` for
  highlighting, `<kbd>` and `<span class="menuseq">` for keyboard and menu macros, footnote references as Markdown
  footnotes are. Anything else keeps its tags and loses unknown attributes.
- Asciidoctor's stylesheet, its icon fonts, `data-uri` and `linkcss` options are never used.
- The page script, the CSP, the custom stylesheet, Mermaid and KaTeX drawing, and HTML, PDF and multi-file export are
  used unchanged: they rely only on the DOM contract and `mdr-*` classes, which AsciiDoc output follows. Source
  highlighting stays out, as for Markdown code blocks.
- Relative image targets resolve against the side's base URI, with `imagesdir` from the document's attributes
  applied first, the same way relative Markdown images do.

## Consequences

- An AsciiDoc diff looks like a Markdown diff: same theme, colors, fonts and controls; a team's custom stylesheet
  styles both.
- It does not look exactly like the team's Asciidoctor site. That is accepted: the view is for reviewing changes,
  and the custom stylesheet can close the gap.
- Export of an AsciiDoc diff needs no work beyond the file filters, and a multi-file export may mix both formats in
  one document (FR-21).
- New AsciiDoc elements need a class, a style and a fixture; the sanitizer's allowlist may need `mark` and a few
  Asciidoctor inline classes, reviewed against NFR-8.
