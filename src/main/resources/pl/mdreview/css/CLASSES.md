# Preview CSS classes (`diff.css`)

Contract between the HTML generator and the `diff.css` stylesheet. All classes use the `mdr-` prefix. Full structure example: `demo.html` (open it in a browser; the button toggles the theme).

## Theme

| Class | Where | Meaning |
|---|---|---|
| `mdr-theme-light` / `mdr-theme-dark` | `<html>` or `.mdr-root` | IntelliJ Light / Darcula theme. The generator sets it from `JBColor.isBright()`. Without a class, `prefers-color-scheme` applies. |

Colors and fonts are CSS variables (`--mdr-bg`, `--mdr-fg`, `--mdr-added-bg`, `--mdr-removed-inline`, `--mdr-font`, `--mdr-mono`, `--mdr-font-size` etc., listed at the top of `diff.css`). The generator can override them with an inline style on `.mdr-root`, e.g. with the editor font.

## View containers

| Class | Element | Meaning |
|---|---|---|
| `mdr-root` | `div` | Document root, always present. |
| `mdr-sbs` | together with `mdr-root` | Side-by-side view (requirement 1). Removed: red background, no strikethrough. |
| `mdr-merged` | together with `mdr-root` | Merged view (requirement 2). Removed: red background and strikethrough. |
| `mdr-sbs-header` | `div` with two children | Sticky header with file names/revisions (left, right). |
| `mdr-row` | `div` in `.mdr-sbs` | Grid row: exactly two `.mdr-side` + optional `.mdr-actions`. Matching blocks share a row, so they stay aligned. Attribute `data-block-id`. |
| `mdr-side mdr-left` / `mdr-side mdr-right` | `div` in `.mdr-row` | Left (old) and right (new) cell of the row. |
| `mdr-placeholder` | `div` in `.mdr-side` | Empty space on the side that lacks the block (added/removed). Hatched background. |
| `mdr-empty` | `div` | "No differences" / empty document message. |
| `mdr-notice` | `div` | Boxed notice (e.g. JCEF unavailable, file too large). |

## Blocks

`mdr-block` marks one diff block: heading, paragraph, list item (`li`), table row (`tr`), code block, quote, front matter. Always with exactly one status class and a `data-block-id` attribute (the same id on both sides of the side-by-side view and on the buttons).

| Status class | Render |
|---|---|
| `mdr-equal` | no markers |
| `mdr-added` | green background + green bar on the left |
| `mdr-removed` | red background + red bar; in `.mdr-merged` also struck through |
| `mdr-modified` | blue bar; details as inline changes |
| `mdr-moved` | purple bar and a light gradient; optional `data-moved-from` / `data-moved-to` with a block id |

Additional block classes:

| Class | Meaning |
|---|---|
| `mdr-meta-changed` | Change with no text difference (heading level, table alignment, code info string, checkbox state). Description text in the `data-change-note` attribute, shown under the block. |
| `mdr-current` | Currently selected change during navigation (F7 / Shift+F7): outline, buttons always visible. Can go on `.mdr-block` or `.mdr-row`. |
| `mdr-frontmatter` | YAML front matter block (on `.mdr-block`, with `<pre><code>` inside). Shows a "front matter" label. |
| `mdr-task` | Task list item (`li` with `<input type="checkbox" disabled>`). With `mdr-meta-changed` the checkbox is outlined. |
| `mdr-alert-title` | Title line of a GitHub alert (`p`, its own block), e.g. "Note". A changed type carries `mdr-meta-changed`. |
| `mdr-footnote` | Footnote definition (`div`, its own block) with `id="mdr-fn-<side>-<label>"`; children `.mdr-fn-label` and `.mdr-fn-body`. |
| `mdr-html` | Raw HTML block (`div`), sanitized; word marks on its visible text. A change only in the markup carries `mdr-meta-changed` ("HTML changed"). A single-block `<details>` inside renders `open` when the block changed. |
| `mdr-comment` | HTML comment block (`div`, `title="HTML comment"`), child `.mdr-comment-text` drawn as `<!-- text -->`. An unchanged comment's row has the `hidden` attribute. |
| `mdr-details-title` | Summary of a `<details>` spanning several blocks (`div`, its own block), with an empty `.mdr-details-marker` span drawing ▸ / ▾. |

## HTML `<details>`

A `<details>` element spanning several Markdown blocks is rendered as `<details class="mdr-details">`, `open` when anything inside changed, around its rows: the `<summary class="mdr-details-summary">` holds the `.mdr-details-title` row, the content rows follow. Unchanged rows inside fold separately (`details.mdr-fold`).

## GitHub alerts and footnotes

| Class | Where | Meaning |
|---|---|---|
| `mdr-alert` + `mdr-alert-<type>` | outermost `blockquote` around every block of an alert | Alert of type `note`, `tip`, `important`, `warning` or `caution`; border and title use `--mdr-alert-<type>` (from `--md-alert-<type>`). |
| `mdr-fnref` | `sup` around an `a href="#mdr-fn-<side>-<label>"` | Footnote reference; links to the definition on the same side. |
| `del` without `mdr-del` | inline | Strikethrough written in the Markdown (`~~text~~`): line-through, no background. |

## Inline changes

| Element / class | Meaning |
|---|---|
| `<ins class="mdr-ins">` | Added fragment (stronger green). |
| `<del class="mdr-del">` | Removed fragment (stronger red). Struck through only in `.mdr-merged`. In the side-by-side view `del` is on the left and `ins` on the right; in the merged view they sit next to each other: `del` first, then `ins`. |
| `mdr-ws` | Whitespace-only change visible in the render (e.g. hard line break). |
| `mdr-ws-break` | Together with `mdr-ws`: empty `span` before `<br>`, draws a ↵ symbol. |
| `mdr-link-changed` | On `<a>`: same text, different URL. Old address in `title`. |
| `mdr-img-changed` | On `<img>`: changed `src`/`alt`/`title`. |
| `mdr-img-missing` | On an `<img>` whose file was not found (reserves space). |

## Tables and code

| Class | Where | Meaning |
|---|---|---|
| `mdr-block` + status | `tr` | Table row as a block; background on the cells, bar on the first cell. |
| `mdr-cell-added` / `mdr-cell-removed` | `td`, `th` | A single added/removed cell. |
| `mdr-col-added` / `mdr-col-removed` | `td`, `th`, `col` | Cells of an added/removed column. |
| `mdr-line` + `mdr-added` / `mdr-removed` / `mdr-modified` | `span` in `pre code` | One line of a code block or front matter. Each line is its own `span` (no `\n` between them, `display: block`). |

## Mermaid diagrams

| Class | Where | Meaning |
|---|---|---|
| `mdr-mermaid` | `div.mdr-block` with a `pre.mdr-mermaid-code` | A ` ```mermaid ` fence kept as one block. The source sits in `pre.mdr-mermaid-code`; without the page script it shows as code. |
| `mdr-mermaid-svg` | `div` in `.mdr-mermaid`, added by the page script | The drawn diagram (Mermaid's `svg`). |
| `mdr-mermaid-drawn` / `mdr-mermaid-failed` | on `.mdr-mermaid`, added by the page script | Drawn: the source is hidden. Failed: the source stays visible under a `.mdr-mermaid-error` with Mermaid's message. |
| `mdr-mermaid-row` | `.mdr-row` (side by side) or `.mdr-item` (merged) of a changed diagram | Starts with `details.mdr-mermaid-source`. In the merged view the drawings sit in `.mdr-mermaid-pair`: `.mdr-mermaid-old` (the old one, dimmed), then the new one. |
| `mdr-mermaid-source` | `details` with a `summary` ("Show source changes") | Its `.mdr-mermaid-lines` hold the source line diff as nested rows or items without `data-idx`. While open, the drawings are hidden. The page script opens it when a drawing fails. |

## Moved blocks

| Class | Where | Meaning |
|---|---|---|
| `mdr-moved` | `.mdr-row` / `.mdr-item` of either end of a move, next to `blk-removed` or `blk-added` | The block moved without changes (FR-15). The element also has `data-moved-to` (old place) or `data-moved-from` (new place) with the other end's `data-idx`. In the merged view the old place is dimmed, not struck through. |
| `mdr-moved-note` | `div` at the end of the side (side by side) or item (merged) | Holds `a.mdr-moved-link` with `data-goto="<idx>"` and a label such as "Moved down"; the page script scrolls to that block on click. |

## Math

| Class | Where | Meaning |
|---|---|---|
| `mdr-math` + `mdr-math-inline` | `span` in text | An inline formula (`$...$`). It holds the TeX source; the page script draws it in place with KaTeX. Marked as a whole by `ins.mdr-ins` / `del.mdr-del` when it changed. |
| `mdr-math` + `mdr-math-display` | `div` in `div.mdr-block.mdr-math-block` | A block formula (`$$...$$`), drawn in display mode. A `$$...$$` inside a paragraph is a `span` with the same classes. |
| `mdr-math-drawn` / `mdr-math-failed` | on `.mdr-math`, added by the page script | Drawn: KaTeX's output replaced the source. Failed: the source stays, underlined, with KaTeX's message as its tooltip and, for a block formula, in a `.mdr-math-error` below it. Without the script the source shows as monospace text. |
| `mdr-math-row` | `.mdr-row` (side by side) or `.mdr-item` (merged) of a changed block formula | Starts with `details.mdr-math-source`. In the merged view the formulas sit in `.mdr-math-pair`: `.mdr-math-old` (the old one, dimmed), then the new one. |
| `mdr-math-source` | `details` with a `summary` ("Show source changes") | Its `.mdr-math-lines` hold the source line diff as nested rows or items without `data-idx`. While open, the formulas are hidden. The page script opens it when a formula fails to draw. |

## Per-block actions

```html
<div class="mdr-actions">
  <button class="mdr-revert" data-block-id="b7" title="Revert block">⟲ Revert</button>
</div>
```

- In the merged view `.mdr-actions` is the last child of `.mdr-block`; in the side-by-side view it is the last child of `.mdr-row`.
- Absolutely positioned in the top right corner; visible on hover, on `:focus-within` and for `.mdr-current`.
- `mdr-revert` reverts the block on the right side, `mdr-apply` (side-by-side view only) copies the right version to the left side (P2.5). The IDE's page script adds them, only for sides that can be edited; other buttons in `.mdr-actions` would look the same. The block source editor (`.mdr-editor`, FR-13) is styled by the IDE page, not by this stylesheet. Click handling (JBCefJSQuery) belongs to the generator; the CSS does not assume it.
- In the merged view the IDE puts `.mdr-actions` as the last child of `.mdr-item` and styles it there.
- `button:disabled` is greyed out (e.g. a read-only file).
