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
`RenderRequest(left, right, mode, options, leftBaseUri, rightBaseUri)`: texts with `\n` line separators, `null` = the file does not exist;
`mode` = `SIDE_BY_SIDE` or `MERGED`; `options.ignoreWhitespace` (FR-7), `options.collapseUnchanged`,
`options.loadRemoteImages` (NFR-8, default `false`: remote `src` in raw HTML is blanked),
`options.renderMermaid` (P2.8, default `true`: each ` ```mermaid ` fence is one block; `false` diffs it line by line),
`options.pairingThreshold` (minimum word similarity for a removed and an added block to pair as one modified block;
default `DiffOptions.DEFAULT_PAIRING_THRESHOLD`, halved for a block that is the only one of its kind in its window).
`leftBaseUri` / `rightBaseUri` (optional): URI of the directory holding each side's file, ending with `/`
(`file:///home/me/project/docs/`). Relative image paths on that side resolve against it; `null` keeps them as written.
Absolute URLs, root-relative paths (`/img.png`) and fragments are never rewritten.

The IDE does not call `render()` for a side above `LargeFiles.limit()` (default `SizeLimit.DEFAULT`: 20,000 lines or 2 MB, NFR-4); it shows a notice
with an **Open Text Viewer** button instead.
`render()` is called off the EDT, inside a read action, possibly concurrently: no state, no IntelliJ APIs, no network.

## Output: `RenderedDiff(bodyHtml, css, blocks)`
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

## What the IDE does
- Registers two viewers in IntelliJ's diff window (`diff.DiffTool`): "Rendered Markdown (side by side)" and "(merged)"; the text diff is the built-in viewer.
- JCEF panel, change navigation, Ctrl/Cmd+click on a block opens its source in the editor (FR-14), toggles for ignoring whitespace and collapsing unchanged blocks in the diff window's settings menu.
- The "Compare Markdown Files..." action (project view context menu and Tools menu).
- Re-runs the diff after a document change (300 ms debounce) and after a theme change, keeping the scroll position.
- Fills `leftBaseUri` / `rightBaseUri` from the directory of each `DocumentContent`'s local file, so images next to the
  compared file render (the page's CSP allows `file:` and `data:` images, plus `http:` / `https:` when the
  "Load remote images" setting is on, which also sets `options.loadRemoteImages`).
- Draws Mermaid diagrams: when `bodyHtml` has a `.mdr-mermaid` block, the page inlines the bundled `mermaid.min.js`
  (`src/main/resources/pl/mdreview/mermaid`, version and license in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md))
  and a script that draws each block with Mermaid's `default` or `dark` theme, matching the editor scheme. Both scripts
  carry the page's nonce, so the CSP is unchanged and nothing is loaded from the network. A diagram Mermaid cannot
  parse shows its error and source; for a changed diagram the source line diff opens.
- Shows the large-file notice above `LargeFiles.limit()`; its button marks the diff context so the rendered viewers
  report `canShow = false` for files above the limit, then reopens the request, which falls back to the text viewer.
- Settings | Tools | Markdown Review (`MarkdownReviewSettings`, stored in `markdownReview.xml`): the default viewer,
  `ignoreWhitespace`, `collapseUnchanged`, the large-file limits (lines, KB), loading remote images, rendering
  Mermaid diagrams, `showBlockActions` and the pairing threshold.
- Per-block buttons (FR-12): the page's script adds `.mdr-actions` with `mdr-revert` (writes the left version into
  the right document) and, in the side-by-side view, `mdr-apply` (writes the right version into the left document)
  to every block with an `edit` region, only for sides whose `DocumentContent` can be made writable and that the
  request does not mark `FORCE_READ_ONLY` (so never on VCS revisions). A click sends `edit|<action>|<idx>|<stamp>`
  with the documents' modification stamps at render time; a different stamp refuses the edit and re-renders. The edit
  is one global `WriteCommandAction` ("Revert Markdown Block" / "Apply Markdown Block"), so one Undo restores it
  from the diff window or the editor.
- Makes the chosen default viewer the first one IntelliJ tries for Markdown diffs through the diff tool order; see
  [ADR 0001](adr/0001-default-diff-viewer.md).
- Recognizes Markdown by extension (`md`, `markdown`, `mdown`, `mkd`) or, when the bundled Markdown plugin is enabled,
  by its file type (optional dependency, `markdown-review-withMarkdown.xml`). Rendering always uses commonmark.
