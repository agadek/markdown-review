# Core ↔ IDE contract

The IDE layer (`pl.mdreview.ide`) uses the diff engine and HTML generator only through the
`pl.mdreview.api.MarkdownDiffRenderer` interface ([MarkdownDiffApi.kt](../src/main/kotlin/pl/mdreview/api/MarkdownDiffApi.kt)).
The `pl.mdreview.api` package does not depend on the IntelliJ Platform.

## What the core provides
1. A class implementing `MarkdownDiffRenderer` (no-arg constructor) that combines the engine (M1) and the HTML generator (M2);
   currently `pl.mdreview.render.CoreMarkdownDiffRenderer`.
2. The file `src/main/resources/META-INF/services/pl.mdreview.api.MarkdownDiffRenderer` with that class's fully qualified name.
   Without it the IDE uses `FallbackRenderer` (raw text, paragraph diff), so the plugin still runs.

## Input
`RenderRequest(left, right, mode, options, leftBaseUri, rightBaseUri, format)`: texts with `\n` line separators, `null` = the file does not exist;
`mode` = `SIDE_BY_SIDE` or `MERGED`; `options.ignoreWhitespace` (FR-7), `options.collapseUnchanged`,
`options.loadRemoteImages` (NFR-8, default `false`: remote `src` in raw HTML is blanked),
`options.renderMermaid` (P2.8, default `true`: each Markdown ` ```mermaid ` fence or AsciiDoc `[mermaid]` block is one
block; `false` diffs it line by line),
`options.pairingThreshold` (minimum similarity of the content words, leaving out function words such as "the", for
a removed and an added block to pair as one modified block;
default `DiffOptions.DEFAULT_PAIRING_THRESHOLD`, halved for a block that is the only one of its kind in its window).
`leftBaseUri` / `rightBaseUri` (optional): URI of the directory holding each side's file, ending with `/`
(`file:///home/me/project/docs/`). Relative image paths on that side resolve against it; `null` keeps them as written.
Absolute URLs, root-relative paths (`/img.png`) and fragments are never rewritten.
`format` (optional, default `DocumentFormat.MARKDOWN`): the format of both sides, `MARKDOWN` or `ASCIIDOC`; both sides
of one request have the same format. Markdown always has a front end. AsciiDoc has one only while the optional
integration of the JetBrains AsciiDoc plugin is installed (`pl.mdreview.engine.asciidoc.AsciidoctorProvider`); a
request for it without a provider falls back to raw text compared paragraph by paragraph, and never throws. The
`format` is echoed in `RenderedDiff.format`, so the IDE can label its block editor and source.

Apart from `format` and the Markdown-specific `renderMermaid` option, the rest of this contract is
format-independent: blocks, statuses, HTML structure, source lines, edit regions and change navigation have the same
meaning for every format a front end provides.

The IDE does not call `render()` for a side above `LargeFiles.limit()` (default `SizeLimit.DEFAULT`: 20,000 lines or 2 MB, NFR-4); it shows a notice
with an **Open Text Viewer** button instead.
`render()` is called off the EDT, inside a read action, possibly concurrently: no state, no IntelliJ APIs, no network.

## Output: `RenderedDiff(bodyHtml, css, blocks, format)`
- `format`: the `DocumentFormat` of both sides, `MARKDOWN` when not given. The IDE uses it for the block editor's
  label ("Markdown" or "AsciiDoc").
- `bodyHtml`: a fragment for `<body>`, **with no scripts and no `on*` attributes** (the page runs under a CSP that allows only the IDE's script).
- Every block (grid row / merged-view block): `data-idx="<idx>"` and the class `blk-equal|blk-modified|blk-removed|blk-added`.
- The element holding one side's content: `data-side="left|right"` and `data-line="<zero-based start line>"`; a placeholder has `data-side` only.
- Collapsed unchanged blocks: `<details>`/`<summary>` (navigation expands them itself).
- Links as plain `<a href>`; the IDE opens external ones in the browser, `#anchors` work within the page.
- `css`: colors and fonts only from CSS variables that the IDE sets from the current editor color scheme:
  `--md-bg --md-fg --md-muted --md-border --md-link --md-code-bg --md-removed-bg --md-added-bg --md-modified-bg
  --md-placeholder-bg --md-removed-word-bg --md-added-word-bg --md-font --md-font-size --md-mono-font`.
  No `prefers-color-scheme`.
- Changes the word diff cannot show (P2.3):
  - a link or image whose text (alt text) is equal but whose target changed, including through a changed reference
    definition (`[label]: url`), makes its block `MODIFIED`; the element gets `mdr-link-changed` (`<a>`) or
    `mdr-img-changed` (`<img>`) and a `title` naming the other side's URL (`Previous URL: …` on the right and in the
    merged view, `New URL: …` on the left). Links pair by text and occurrence within the block;
  - a heading whose level changed gets `mdr-meta-changed` and `data-change-note="h3 → h4"` on its block element on
    the new side, like the checkbox and alert-type notes.
- Mermaid diagrams (P2.8, with `options.renderMermaid`): a ` ```mermaid ` fence is one block whose line range covers
  the whole fence. Its side holds `div.mdr-block.mdr-mermaid` with the source in `pre.mdr-mermaid-code`, which the IDE's
  page script draws. A modified diagram's row (or merged item) also carries `mdr-mermaid-row` and starts with
  `details.mdr-mermaid-source`, holding the source line diff as nested rows or items with `data-side` / `data-line`
  but no `data-idx`. See `CLASSES.md`.
- Math (P2.4, P2.8): an inline formula is `span.mdr-math.mdr-math-inline` holding its TeX source, marked as a whole by
  the word diff (`<del class="mdr-del">` / `<ins class="mdr-ins">` around the span). A block formula is one `MATH`
  block whose line range covers the whole `$$ ... $$`; its side holds `div.mdr-block.mdr-math-block` with the source
  in `div.mdr-math.mdr-math-display`. A modified block formula's row (or merged item) carries `mdr-math-row` and starts
  with `details.mdr-math-source`, like a changed diagram. The IDE's page script draws the formulas.
- Raw HTML (NFR-8): blocks and inline HTML go through an allowlist sanitizer (`HtmlFragment`): GitHub's set of
  tags and attributes, `http`, `https`, `mailto`, `file` and relative URLs (`data:image/*` for images); the content of
  `<script>`, `<style>`, `<iframe>` and similar elements is dropped. Changes only in HTML markup or in an HTML comment
  get `mdr-meta-changed` with the note `HTML changed` or `comment changed`.
- A `<details>` spanning several blocks wraps its rows in `<details class="mdr-details">` (open when it contains a
  change); the summary row is a block of its own with `data-idx` like any other. An unchanged HTML comment row has
  the `hidden` attribute.
- `blocks`: `BlockInfo(idx, status, left: LineRange?, right: LineRange?, edit: EditRegion?)` in display order;
  zero-based, end-exclusive line ranges. The IDE builds F7/Shift+F7 navigation and the "Change 3 of 12" counter from
  them.
- `edit` (P2.5, additive): the lines per-block revert and apply exchange, one `LineRange` per side. Replacing
  `edit.right` with the lines of `edit.left` reverts the change; the reverse applies it to the left side
  (`TextEdit.replaceLines` turns that into one text replacement). A modified block exchanges its own lines plus the
  uncovered lines that follow it directly (a setext underline, a table's delimiter row). An added or removed block
  exchanges, on each side, everything from the end of the previous block on that side to the start of the next one,
  so the blank lines around it come along and the side without it gets an insertion (an empty range where it goes).
  A fenced code block, front matter or table added or removed as a whole is one region for all its blocks, closing
  fence or delimiter row included. `null` for equal blocks and for changes no line edit can undo, such as a block
  that changed only through a link reference definition.
- AsciiDoc edit regions (P4.5): a modified block's region starts at its prefix lines (a style attribute, anchor,
  block title or `+` continuation) and ends at its own last line. A delimited code block, table, container or the
  document header added or removed as a whole is one region with both delimiters; a list item with continuations
  brings its attached blocks. A change inside an existing group stays inside its opening and closing delimiters.
  A table header row whose `[options="header"]` changed exchanges the table's attribute line too, and a block title
  added or removed is inserted on its own line where the titled block starts. An equal block may carry a region when
  only an anchor or attribute above it changed, so the source line can be reverted against the unchanged block.
- `movedFrom` / `movedTo` (P2.7, FR-15, additive): a removed and an added block with equal keys are one move. The
  statuses stay `REMOVED` and `ADDED`; the added end's `movedFrom` and the removed end's `movedTo` name the other end's
  `idx`. In `bodyHtml` both ends' block elements carry `mdr-moved` and `data-moved-from` / `data-moved-to`, and end
  with `div.mdr-moved-note` holding `a.mdr-moved-link[data-goto="<idx>"]` ("Moved down", "Moved here from above", ...).
  A move has no word diff. Code lines, fences, front matter lines, thematic breaks, comments and blocks without visible
  text never move. Reverting or applying either end applies both ends' `edit` regions in one command, so the block
  goes back to its old place.

## Supported Markdown
commonmark-java with these extensions: GFM tables, strikethrough, task list items, autolinks, footnotes, GitHub
alerts, plus emoji shortcodes (`EmojiExtension`, GitHub names from `src/main/resources/pl/mdreview/emoji.txt`;
unknown ones stay as text). Front matter is recognized before parsing. Block kinds (`BlockKind`):

| Kind | Element |
|---|---|
| `HEADING`, `PARAGRAPH`, `LIST_ITEM`, `TABLE_ROW` | headings, paragraphs, list items, table rows |
| `CODE_FENCE`, `CODE_LINE` | a code block's opening fence (info string) and each of its lines |
| `THEMATIC_BREAK` | `---`, `***`, `___`; the syntax is not compared |
| `HTML` | a raw HTML block, compared by its visible text |
| `HTML_COMMENT` | an HTML block that is exactly one `<!-- comment -->`, compared by its text |
| `DETAILS` | the opening of a `<details>` element that spans several blocks (its summary); its content blocks have `BlockContext.details` > 0, the closing `</details>` is not a block |
| `FRONT_MATTER` | a line of YAML front matter |
| `FOOTNOTE` | a footnote definition |
| `ALERT` | the title line of a GitHub alert |
| `MATH` | a block formula (`$$ ... $$`), compared exactly except for trailing whitespace; `Block.lines` holds its source lines for the source diff |

Hard line breaks (two trailing spaces or `\`) are part of a block's key and appear as `\n` in its word diff, so
adding or removing one is a change; switching between the two syntaxes is not. With `ignoreWhitespace` they are
ignored.

Math (`MathExtension`, P2.4): `$...$` is an inline formula (`InlineMath`) when the opening `$` is not followed by
whitespace and the closing `$` is not preceded by whitespace or followed by a digit, as on GitHub, so prices such as
"$5 or $10" stay text; code spans and `\$` come first. `$$` on a line of its own opens a block formula (`MathBlock`)
closed by a line ending in `$$`; it does not interrupt a paragraph, and `$$...$$` within a line is an inline formula
in display mode. In a block's plain text an inline formula is its source with its dollars, and `Block.atoms` lists
those ranges so the word diff keeps each formula whole.

## Supported AsciiDoc

The AsciiDoc front end is `pl.mdreview.engine.asciidoc.AsciiDocBlockSource`. It parses each side with AsciidoctorJ
(`load`, `sourcemap: true`, `safe: SECURE`, no base directory and no output file), walks the document tree, and
merges scanner rows for the things the tree does not expose. It reads no files and no network (NFR-13), and a
document Asciidoctor cannot process falls back to raw blank-line-separated paragraphs for that side, so a diff never
fails because of the parser.

### `AsciidoctorProvider`

`pl.mdreview.engine.asciidoc.AsciidoctorProvider` is the only way the core gets an `org.asciidoctor.Asciidoctor`; the
interface is in the AsciiDoc front end's package. The IDE implements it with
`pl.mdreview.ide.asciidoc.PluginAsciidoctorProvider`, an application service declared only in
`markdown-review-withAsciiDoc.xml`, so it loads only while the JetBrains AsciiDoc plugin is installed and enabled. The
provider creates one Asciidoctor instance lazily for the session, with the AsciiDoc plugin's class loader as the
thread context class loader, and closes it when either plugin unloads. A startup activity installs the provider in
`AsciiDocSupport` and in the renderer; until then AsciiDoc requests are not offered to the viewer (NFR-11).

No class outside `pl.mdreview.engine.asciidoc` and `pl.mdreview.ide.asciidoc` imports `org.asciidoctor.*`, so
without the AsciiDoc plugin none of those classes loads and Markdown is unaffected (NFR-15).

### Block kinds

AsciiDoc reuses the Markdown kinds where the meaning is the same and adds five kinds. The unchanged kinds are:
`HEADING`, `PARAGRAPH`, `LIST_ITEM`, `TABLE_ROW`, `CODE_FENCE`, `CODE_LINE`, `THEMATIC_BREAK`, `HTML`, `ALERT`,
`HTML_COMMENT`, `FOOTNOTE`, `MERMAID` and `MATH`.

| Kind | AsciiDoc block |
|---|---|
| `HEADING` | the document title (`= Title`, level 1), a section title (`==` to `======`, level = section level + 1) or a discrete heading. A changed level gets the `h3 → h4` note. |
| `PARAGRAPH` | a paragraph; a literal paragraph becomes `CODE_LINE`s. |
| `LIST_ITEM` | an unordered, ordered, checklist or callout item, and the description of a description-list entry. `BlockContext.listDepth`, `ordered`, `number` and the checklist key apply as for Markdown; blocks attached with `+` follow as blocks of the item. |
| `DESCRIPTION_TERM` | the term of a description-list entry (`term:: description`); the description is a `LIST_ITEM` and both share the entry's source line. |
| `TABLE_ROW` | one table row; header rows are keyed `th:`. Cells of style `a` are compared by their text. |
| `CODE_FENCE`, `CODE_LINE` | listing, source and literal blocks (`----`, `....`): the fence carries style and language, each line is a block. Callout markers stay in the line text. |
| `THEMATIC_BREAK` | `'''` and the page break `<<<`; the kind of break is in the key. A page break renders `hr.mdr-page-break`. |
| `HTML` | a passthrough block (`++++`), through the same sanitizer as Markdown HTML. |
| `HTML_COMMENT` | a `//` line comment, a `////` block comment, or an `ifdef` / `ifndef` / `ifeval` / `endif` directive line. Hidden unless changed. |
| `ALERT` | an admonition paragraph (`NOTE: ...`). The type is in the context and the key, and a changed type gets the existing alert-type note. An admonition block (`[NOTE]` plus `====`) is a container instead. |
| `ATTRIBUTE` | an attribute entry (`:name: value`) in the header or body. Author and revision header lines are `ATTRIBUTE` rows too. Compared as written. |
| `BLOCK_TITLE` | a block title (`.Title`), its own block directly before the block it titles. |
| `IMAGE` | a block image (`image::target[alt]`); `Block.target` holds the target and `Block.plainText` the alt text, so a changed target is a change. Block images render as `figure.mdr-image`, with `figcaption` from a title. |
| `INCLUDE` | an `include::target[]` directive; `Block.target` is the target, never resolved. |
| `FOOTNOTE` | an AsciiDoc footnote definition (`footnote:[text]` or `footnote:id[text]`), emitted after the document's other blocks from Asciidoctor's footnote catalog. `Block.label` is the explicit id when there is one, otherwise the footnote text (with an occurrence suffix for duplicates); references carry that label and resolve to the definition on the same side. A footnote definition has `edit = null`; the paragraph containing its macro carries the edit. |
| `MERMAID` | a `[mermaid]` listing or literal block when `options.renderMermaid` is on (P4.7); `Block.lines` holds the fence and code lines for the source diff. |
| `MATH` | a `[latexmath]` / `[asciimath]` block, or a `[stem]` block when `stem` is `latexmath` or AsciiMath (P4.7, P4.9); `Block.plainText` is the source and `Block.lines` its source lines. AsciiMath conversions are in `Block.mathTex`. |

`FRONT_MATTER` and `DETAILS` are Markdown-only; the AsciiDoc front end does not produce them. AsciiDoc footnotes and
inline HTML are sanitized like the rest of the inline content.

### Contexts, keys and edit metadata

- `BlockContext.containers: List<Container>` holds the delimited blocks a block sits in, outermost first;
  `Container(kind, type, title)` uses the kinds `example`, `sidebar`, `quote`, `verse`, `open`, `admonition`,
  `description` and `callout`. `quoteDepth`, `alert`, `details` and the list fields keep their Markdown meaning and
  are empty/zero where AsciiDoc has no equivalent.
- `Block.key` combines the kind, the pairing context (list depth, ordered, checklist state, container kinds and
  types, table header flag), the block's own source and its plain text. The source catches a change only in markup
  (a link target, bold added); the plain text catches a block whose source is equal but whose rendered text changed
  because an attribute it references changed elsewhere.
- `Block.plainText` is the visible text of the block's converted inline HTML, after attribute substitution and with
  whitespace collapsed outside `<pre>`. The same text runs are stored as `BlockContent.Html`, so word marks apply to
  exactly what the page shows.
- `Block.prefixLines` lists the zero-based source lines directly above `Block.source` that belong to the block's edit
  region: style attributes (`[source,java]`, `[NOTE]`), anchors (`[[id]]`), a `.Title`, and a `+` continuation. The
  lines are sorted and are outside the block's display range.
- `Block.editGroups: List<BlockGroup>` describes the delimited or attached groups the block belongs to, outermost
  first. A `BlockGroup` has a kind (`CODE`, `TABLE`, `CONTAINER`, `LIST_ITEM`, `HEADER`), a start and end line, the
  opening and closing delimiter lines and its own `prefixLines`. `AsciiDocRegionRules` uses them to keep a revert or
  apply inside existing delimiters and to exchange a whole group when every block in it was added or removed.
- A change derived from another edit (an admonition container's style line, an attribute value, a footnote macro)
  can have `BlockInfo.edit = null`; reverting the source edit resolves it. A block whose lines cannot be determined
  is displayed but has no edit region.
- `include::` is never resolved. Conditional content hidden by `ifdef` is not shown on either side; the directive
  line itself is a hidden-unless-changed `HTML_COMMENT` row, so a changed condition is reviewable.

### Output

The DOM contract in "Output" above is unchanged: rows, sides, statuses, `data-idx`, `data-side`, `data-line` and
`BlockInfo` mean the same thing, and AsciiDoc adds only the classes listed in
[CLASSES.md](../src/main/resources/pl/mdreview/css/CLASSES.md): `mdr-container*`, `mdr-block-title`, `mdr-dlist`,
`mdr-callout`, `mdr-include`, `mdr-attribute`, `mdr-image`, `mdr-alert-body` and `mdr-page-break`. `MERMAID` and
`MATH` reuse the
Markdown `.mdr-mermaid*` and `.mdr-math*` markup and page scripts, so the source-diff toggle and the Mermaid/KaTeX
settings work unchanged.

## What the IDE does
- Registers two viewers in IntelliJ's diff window (`diff.DiffTool`): "Rendered (side by side)" and "Rendered (merged)";
  the text diff is the built-in viewer. Both viewers serve Markdown and AsciiDoc.
- Accepts an AsciiDoc request only while the optional integration is available; without the AsciiDoc plugin the
  viewers report `canShow = false` for AsciiDoc and the text viewer stays. A pair of different formats, such as a
  rename from `.md` to `.adoc`, keeps the text diff. While Asciidoctor starts, the panel shows "Preparing AsciiDoc
  renderer..." until the first render can run.
- JCEF panel, change navigation, Ctrl/Cmd+click on a block opens its source in the editor (FR-14), toggles for ignoring whitespace and collapsing unchanged blocks in the diff window's settings menu.
- Block menu (P3.4): a right-click on an element with `data-side` and `data-line` sends
  `menu|<side>|<idx>|<stamp>|<x>|<y>`; the viewer shows a popup there with Go to Source, Open in Text Diff Here (in a
  diff window: reopens it once with the rendered viewers stepping aside and `DiffUserDataKeys.SCROLL_TO_LINE` at the
  block's first line) and Show History for This Block (for a local file under VCS: selects the block's lines in the
  editor and runs `Vcs.ShowHistoryForBlock`).
- The **Compare Rendered Files...** action (project view context menu and Tools menu; action ID
  `MarkdownReview.CompareFiles` unchanged). It accepts two files of the same supported format.
- Re-runs the diff after a document change (300 ms debounce) and after a theme change, keeping the scroll position.
- Fills `leftBaseUri` / `rightBaseUri` from the directory of each `DocumentContent`'s local file, so images next to the
  compared file render (the page's CSP allows `file:` and `data:` images, plus `http:` / `https:` when the
  "Load remote images" setting is on, which also sets `options.loadRemoteImages`).
- Draws Mermaid diagrams: when `bodyHtml` has a `.mdr-mermaid` block, the page inlines the bundled `mermaid.min.js`
  (`src/main/resources/pl/mdreview/mermaid`, version and license in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md))
  and a script that draws each block with Mermaid's `default` or `dark` theme, matching the editor scheme. Both scripts
  carry the page's nonce, so the CSP is unchanged and nothing is loaded from the network. A diagram Mermaid cannot
  parse shows its error and source; for a changed diagram the source line diff opens.
- Draws formulas when the "Render math" setting is on and `bodyHtml` has a `.mdr-math` element: the page inlines the
  bundled `katex.min.js` and `katex.min.css` (`src/main/resources/pl/mdreview/katex`), with the WOFF2 fonts as
  `data:` URIs and `font-src data:` added to the CSP, and a script that renders each formula in place in batches.
  A formula KaTeX cannot parse keeps its source with `mdr-math-failed` and KaTeX's message; for a changed block
  formula the source line diff opens. With the setting off the formulas stay monospace source.
- Shows the large-file notice above `LargeFiles.limit()`; its button marks the diff context so the rendered viewers
  report `canShow = false` for files above the limit, then reopens the request, which falls back to the text viewer.
- Settings | Tools | Markdown Review (`MarkdownReviewSettings`, stored in `markdownReview.xml`): the default viewer,
  `ignoreWhitespace`, `collapseUnchanged`, the large-file limits (lines, KB), loading remote images, rendering
  Mermaid diagrams, rendering math, `showBlockActions` and the pairing threshold.
- Per-block buttons (FR-12): the page's script adds `.mdr-actions` with `mdr-revert` (writes the left version into
  the right document) and, in the side-by-side view, `mdr-apply` (writes the right version into the left document)
  to every block with an `edit` region, only for sides whose `DocumentContent` can be made writable and that the
  request does not mark `FORCE_READ_ONLY` (so never on VCS revisions). A click sends `edit|<action>|<idx>|<stamp>`
  with the documents' modification stamps at render time; a different stamp refuses the edit and re-renders. The edit
  is one global `WriteCommandAction` ("Revert Block" / "Apply Block"), so one Undo restores it from the diff window
  or the editor. The same commands serve both formats.
- Block source editing (FR-13): a double-click on an element with `data-side` and `data-line` of an editable side
  (both sides side by side, the right side merged; independent of `showBlockActions`) sends
  `open|<side>|<idx>|<stamp>`. The viewer answers with the block's lines on that side (`BlockInfo.left` / `right`)
  from the text the page was rendered from, and the page shows them in a `.mdr-editor` textarea in place of the block.
  Ctrl/Cmd+Enter sends `edit|edit-<side>|<idx>|<stamp>|<source>`, which replaces those lines in one global
  `WriteCommandAction` ("Edit Block"), with the same stamp check as the buttons; an emptied editor removes the lines.
  The editor's label says "Markdown" or "AsciiDoc" from `RenderedDiff.format`. Esc closes the editor (`closed`). While
  the editor is open, new pages are held back and the latest is shown when it closes; a refused save keeps the editor
  open with a note, so the text can be copied.
- Makes the chosen default viewer the first one IntelliJ tries for Markdown and AsciiDoc diffs through the diff tool
  order; see [ADR 0001](adr/0001-default-diff-viewer.md).
- Recognizes Markdown by extension (`md`, `markdown`, `mdown`, `mkd`) or, when the bundled Markdown plugin is enabled,
  by its file type (optional dependency, `markdown-review-withMarkdown.xml`). Rendering always uses commonmark.
- Recognizes AsciiDoc by extension (`adoc`, `asciidoc`) and, while the AsciiDoc plugin is enabled, by its file type
  (`markdown-review-withAsciiDoc.xml`, `pl.mdreview.ide.asciidoc.AsciiDocPluginFileTypeRecognizer`), which also
  covers `.asc` files mapped to that type. AsciiDoc rendering uses AsciidoctorJ from that plugin.
