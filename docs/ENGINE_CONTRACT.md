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
`RenderRequest(left, right, mode, options)`: texts with `\n` line separators, `null` = the file does not exist;
`mode` = `SIDE_BY_SIDE` or `MERGED`; `options.ignoreWhitespace` (FR-7), `options.collapseUnchanged`.
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
- `blocks`: `BlockInfo(idx, status, left: LineRange?, right: LineRange?)` in display order; zero-based, end-exclusive line
  ranges. The IDE builds F7/Shift+F7 navigation and the "Change 3 of 12" counter from them, and per-block revert in M6.

## What the IDE does
- Registers two viewers in IntelliJ's diff window (`diff.DiffTool`): "Rendered Markdown (side by side)" and "(merged)"; the text diff is the built-in viewer.
- JCEF panel, change navigation, Ctrl/Cmd+click on a block opens its source in the editor (FR-14), toggles for ignoring whitespace and collapsing unchanged blocks in the diff window's settings menu.
- The "Compare Markdown Files..." action (project view context menu and Tools menu).
- Re-runs the diff after a document change (300 ms debounce) and after a theme change, keeping the scroll position.
