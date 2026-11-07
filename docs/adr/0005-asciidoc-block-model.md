# ADR 0005: How an AsciiDoc document becomes blocks

Status: proposed (P4.1 checks the line mapping), phase 4.

## Context

[ADR 0004](0004-format-neutral-engine.md) lets a front end supply `Block`s to the shared engine. Each block needs a
kind, a key (what the block diff compares), a plain text (what the word diff compares), a context, a source line
range and renderable content. Markdown's blocks are the leaf blocks a reader sees as one unit: a heading, a
paragraph, a list item without its nested list, a table row, one line of code.

Asciidoctor's tree ([ADR 0002](0002-asciidoc-parser.md)) is deeper: sections contain blocks, compound blocks
(example, sidebar, quote, open, admonition with a delimited body) contain blocks, list items carry attached blocks
through list continuations (`+`), and tables contain rows of cells that can hold AsciiDoc content (`a|`). Inline
content is not in the tree; `getContent()` / `getText()` / `getTitle()` return it converted to HTML, after
attribute substitution.

## Decision

### Granularity: the same leaves as Markdown

| AsciiDoc | Block | Notes |
|---|---|---|
| Document title (`= Title`) | `HEADING`, level 1 | |
| Author, revision lines and header attribute entries | `ATTRIBUTE`, one per line | like front matter lines; compared as written |
| Attribute entry in the body (`:name: value`) | `ATTRIBUTE` | |
| Section title (`==` to `======`), discrete heading (`[discrete]`) | `HEADING`, level = section level + 1 | a changed level gets the `h3 → h4` note |
| Paragraph, including `[source]`-less literal paragraphs | `PARAGRAPH` | literal paragraphs as `CODE_LINE`s |
| Admonition paragraph (`NOTE: ...`) | `ALERT` title plus a `PARAGRAPH` in an admonition container | same five types as GitHub alerts; a changed type gets the existing alert-type note |
| Admonition block (`[NOTE]` + `====`) | container of type `admonition`, with its blocks inside | |
| Example `====`, sidebar `****`, quote and verse `____`, open `--` | container (`example`, `sidebar`, `quote`, `verse`, `open`), blocks inside | verse lines are one `PARAGRAPH` with hard breaks |
| Block title (`.Title`) | `BLOCK_TITLE` before the block it titles | so a changed title is its own change |
| List item (`*`, `-`, `.`, `1.`), checklist item (`* [x]`) | `LIST_ITEM` with `BlockContext.listDepth`, `ordered`, `number` | blocks attached by `+` follow as blocks of the item, with one more list depth |
| Description list entry (`term:: description`) | `DESCRIPTION_TERM` and a `LIST_ITEM` for the description | Q&A and horizontal styles render as plain description lists |
| Callout list item (`<1> text`) | `LIST_ITEM`, ordered, in a callout list | |
| Listing, source and literal blocks (`----`, `....`) | `CODE_FENCE` (style and language) plus one `CODE_LINE` per line | `[mermaid]` is `MERMAID` with P4.7, otherwise code |
| Table (`\|===`) | one `TABLE_ROW` per row, header row marked | cells of style `a` are rendered as converted HTML, compared by their text |
| Block image (`image::`) | `IMAGE` | the target is part of the key, so a changed target is a change with the existing image note |
| Thematic break (`'''`), page break (`<<<`) | `THEMATIC_BREAK` (kind of break in the key) | |
| Stem block (`[stem]` + `++++`), `latexmath` | `MATH` with P4.7, otherwise code | |
| Passthrough block (`++++`) | `HTML`, through the same sanitizer as Markdown HTML blocks | |
| `//` line comments and `////` block comments | `HTML_COMMENT` (hidden unless changed) | from the line scanner; not in the tree |
| `include::target[]` line | `INCLUDE` | not resolved; the key is the directive line |
| `toc::[]`, preamble, document | no block | containers only |

New kinds are `ATTRIBUTE`, `BLOCK_TITLE`, `DESCRIPTION_TERM`, `IMAGE` and `INCLUDE`. `IMAGE` stays AsciiDoc-only;
Markdown images remain inline in paragraphs.

### Containers

Compound blocks do not become rows of their own. Like a Markdown blockquote, they are a `BlockContext.containers`
entry on every block inside them, and the renderer draws a frame (bar, box or admonition icon) around consecutive
rows that share it. A container's own delimiters are covered by the line ranges of its first and last inner block
for editing purposes (see "Line ranges"), so revert and apply keep the delimiters balanced.

### Key and plain text

- `plainText` is the visible text of the block's converted inline HTML, as `HtmlFragment.parse` produces it:
  decoded, whitespace collapsed outside `<pre>`. The content is stored as those parts (`BlockContent.Html`), so the
  word marks found on `plainText` apply to the same text runs the page shows.
- `key` is `kind`, the context that matters for pairing (list depth, ordered, checklist state, container types),
  the block's **source** and its `plainText`, normalized the way Markdown keys are (trailing whitespace; all
  whitespace with `ignoreWhitespace`). The source catches changes only in markup (bold added, a changed link
  target); the plain text catches a block whose source is equal but whose rendered text changed because an
  attribute it references changed elsewhere in the file.
- An inline formula (`stem:[...]`, `latexmath:[...]`) is an atom of `plainText` with P4.7, as `$...$` is.

### Line ranges

- The start line of every structural node comes from `getSourceLocation().getLineNumber()` (one-based, made
  zero-based).
- The end line comes from the line scanner: the line before the next block's start (or the container's closing
  delimiter), with trailing blank lines and the block's own attribute lines (`[source,java]`, `[[anchor]]`) handled
  like Markdown's setext underline: inside the block's edit region, outside its display range.
- Nodes the source map does not cover come from the scanner directly: the document header, comments, `include::`
  lines, attribute entries in the body, and, if P4.1 finds them missing, table rows (one per line in the common
  `|a |b` form; a row spread over several lines takes the lines up to the next row's first cell) and description
  list entries.
- A block whose lines cannot be determined gets the range of its parent and no edit region (`edit = null`), so it is
  displayed and navigable but not revertable; the AsciiDoc fixtures assert that no block of theirs falls back.

### Attributes and the preprocessor

- Attribute references are substituted by Asciidoctor, so a block shows what the build shows.
- `ifdef` / `ifndef` / `ifeval` are evaluated by Asciidoctor with the document's own attributes; skipped content is
  not shown on either side. The directive lines are `HTML_COMMENT`-like hidden rows, so a changed condition shows up.
- `include::` is never resolved (`SECURE`): the directive is an `INCLUDE` block that renders as a link to the target,
  relative to the side's base URI.

## Consequences

- The diff rows match what a reader sees as one unit, as in Markdown, and the shared pairing, moves and word diff
  apply unchanged.
- Correct line ranges depend on the scanner; it is the part of the front end most likely to have bugs, and each
  confirmed one gets a fixture (P3.1 rule).
- A table cell with nested AsciiDoc content is compared as text inside its row, not block by block; acceptable for
  phase 4.
- Conditional content hidden by `ifdef` is not reviewed in rendered form; the text diff remains for it.
