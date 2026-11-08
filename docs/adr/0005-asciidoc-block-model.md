# ADR 0005: How an AsciiDoc document becomes blocks

Status: revised and accepted (P4.1 checked the line mapping), phase 4.

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
| Admonition paragraph (`NOTE: ...`) | one `ALERT` block, label generated from `style` | Asciidoctor gives one `admonition` node, not a separate title node; a changed type gets the existing alert-type note |
| Admonition block (`[NOTE]` + `====`) | container of type `admonition`, with its blocks inside | |
| Example `====`, sidebar `****`, quote and verse `____`, open `--` | container (`example`, `sidebar`, `quote`, `verse`, `open`), blocks inside | verse lines are one `PARAGRAPH` with hard breaks |
| Block title (`.Title`) | `BLOCK_TITLE` before the block it titles | so a changed title is its own change |
| List item (`*`, `-`, `.`, `1.`), checklist item (`* [x]`) | `LIST_ITEM` with `BlockContext.listDepth`, `ordered`, `number` | blocks attached by `+` follow as blocks of the item, with one more list depth |
| Description list entry (`term:: description`) | `DESCRIPTION_TERM` and a `LIST_ITEM` for the description | both nodes share the entry's source line; the scanner splits at `::`; Q&A and horizontal styles render as plain description lists |
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
- P4.1 measured 76 nodes in a 145-line sample: every node had a source location and none was line 0 or blank.
  Section titles, paragraph starts, list items, table cells, include lines and delimited-block opening delimiters
  were right. The exceptions are in "Spike results" below.

### Attributes and the preprocessor

- Attribute references are substituted by Asciidoctor, so a block shows what the build shows.
- `ifdef` / `ifndef` / `ifeval` are evaluated by Asciidoctor with the document's own attributes; skipped content is
  not shown on either side. The directive lines are `HTML_COMMENT`-like hidden rows, so a changed condition shows up.
  A paragraph immediately before `endif` can be reported on the `endif` line; the scanner corrects it as described
  in "Spike results".
- `include::` is never resolved (`SECURE`): the directive is an `INCLUDE` block that renders as a link to the target,
  relative to the side's base URI.

## Spike results (P4.1)

The 145-line sample had document title and header attributes, six section levels, a preamble, paragraphs, both
admonition forms, a nested example/sidebar/quote chain and an open block,
unordered/ordered/checklist/description/callout lists with continuations, two tables (one-line, multi-line and `a|`
cells), listing and literal blocks with
`[source,java]` and `[[anchor]]`, block titles, `ifdef`/`endif`, `include::`, line and block comments, and header
and body attribute entries. It produced 76 nodes. The findings:

- **Correct and directly usable:** document line 1, preamble paragraph, section titles and levels, paragraph
  starts (a multi-line paragraph points at its first line), list items and nested list items, table cells, listing
  and literal blocks (opening delimiter), include line, last section and its paragraph. Delimited containers
  (admonition, example, sidebar, quote, open) and tables point at their opening delimiter: a useful start line, not
  the first content line.
- **No end lines:** `Cursor` exposes only `getLineNumber()` (plus path, dir and file). The scanner still computes
  every end line as described above.
- **Conditional tail paragraph:** a paragraph directly followed by `endif::[]` with no blank line is reported on
  the `endif` line. A blank line before `endif` makes the line right, and sections, lists and tables inside the same
  `ifdef` were right. Scanner fallback: if a node's line is an `ifdef`/`ifndef`/`ifeval`/`endif` line, anchor it by
  its source text inside the conditional span, or use the previous non-blank line. Example: paragraph on line 5,
  `endif` on line 6, node reports line 6.
- **Admonition paragraphs:** `NOTE: text` is one `admonition` block with `style=NOTE` and `getSource()` equal to the
  text after `NOTE:`. There is no separate title node; the "Note" label is generated. Map it as one `ALERT` block
  with the label from the style and the converted paragraph inside. `[NOTE]` plus `====` is a container with child
  paragraphs; its start line is the `====` line, and the `[NOTE]` line is not a node.
- **Block titles and attribute lines:** `.Title`, `[source,java]`, `[[anchor]]` and `[NOTE]` are not nodes and are
  outside the block's start line. Measured examples: title line 40 and example start line 41; anchor 105, title 106,
  source 107 and listing start 108; `[NOTE]` 33 and block 34. The scanner attaches them to the display range and edit
  region.
- **Comments and attribute entries:** `//`, `////` and `:body-attr:` are not in the tree. The scanner handles them
  and must skip delimited block bodies so that a `//` inside a listing is not a comment.
- **`include::`:** in `SECURE` mode `include::missing-include.adoc[]` becomes a `paragraph` whose `getSource()` is
  `link:missing-include.adoc[role=include]` and whose content is a link; its line is the directive line. The scanner
  emits the `INCLUDE` block from the `include::` line and suppresses the generated link paragraph. No file is read.
- **List depth:** item lines are right, and list containers need no block. `StructuralNode.getLevel()` is the
  section level, not list depth: every item in the sample reported level 5. Compute `BlockContext.listDepth` from
  the tree. Asciidoctor nests a following list of a different type under the last item of the previous list when it
  follows directly (ordered under unordered, description under ordered); follow the tree, not the markers.
- **List continuations:** the `+` line is not a node. The attached paragraph or listing is a child of the item and
  its start line is right (paragraph 138 and listing delimiter 143 in the sample). The scanner includes the `+` line
  in the item's edit region.
- **Checklist:** `ListItem` has `checkbox` and `checked` attributes present as empty strings when true. Use
  `hasAttribute("checkbox")` and `hasAttribute("checked")`; `getText()` removes `[x]` / `[ ]`.
- **Callout list:** item lines are right. `getMarker()` returned `<1>` for both items, so the marker is not the
  callout number. The list container line can be the last item's line; only the items become blocks.
- **Description list:** term and description share the entry line (`term one:: first description` gives line 75 for
  both nodes). Both blocks can exist, but their line ranges overlap; the scanner splits at `::` and gives both the
  same line, so they share one edit region and one line edit.
- **Table rows:** `Row` has no source location; cells do. In a one-line row all cells report the row line. In a
  multi-line row each cell reports the first line of its cell (row 101/102 in the sample), so group cells by `Row`,
  take the min and max cell lines, and extend to the next row or delimiter with the scanner. `hasHeaderOption()` and
  the header row list identify the header; `colspan`/`rowspan` of 0 mean the default 1. An `a|` cell has style
  `asciidoc`, an `innerDocument` and an inner paragraph on the cell line; compare it by its text.
- **Document header:** author, revision and header attribute entries are not nodes (document line 1). The scanner
  handles them.

## Implementation (P4.3, P4.5)

Implemented as decided, with the five new kinds (`ATTRIBUTE`, `BLOCK_TITLE`, `DESCRIPTION_TERM`, `IMAGE`, `INCLUDE`)
and the shared kinds, contexts and keys above. `AsciiDocLines` handles the header, comments, includes, conditionals,
prefix lines and end lines; `AsciiDocBlockSource` builds the `BlockGroup`s and `prefixLines` that
`AsciiDocRegionRules` uses. Fixtures 50-64 pass with the intended statuses, word diffs, line ranges and containers;
no block in them falls back to a parent range. Two changes derived from another edit (an admonition container type
and an attribute value) have no single-line `edit` region, as recorded for P4.5; reverting the source edit resolves
them.

## Consequences

- The diff rows match what a reader sees as one unit, as in Markdown, and the shared pairing, moves and word diff
  apply unchanged.
- Correct line ranges depend on the scanner; it is the part of the front end most likely to have bugs, and each
  confirmed one gets a fixture (P3.1 rule).
- A table cell with nested AsciiDoc content is compared as text inside its row, not block by block; acceptable for
  phase 4.
- Conditional content hidden by `ifdef` is not reviewed in rendered form; the text diff remains for it.
