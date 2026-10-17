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

## Per-block actions

```html
<div class="mdr-actions">
  <button class="mdr-revert" data-block-id="b7" title="Revert block">⟲ Revert</button>
</div>
```

- In the merged view `.mdr-actions` is the last child of `.mdr-block`; in the side-by-side view it is the last child of `.mdr-row`.
- Absolutely positioned in the top right corner; visible on hover, on `:focus-within` and for `.mdr-current`.
- `mdr-revert` is the revert button placeholder (M6). Other buttons in `.mdr-actions` (e.g. a future `mdr-apply`, `mdr-edit`) look the same. Click handling (JBCefJSQuery) belongs to the generator; the CSS does not assume it.
- `button:disabled` is greyed out (e.g. a read-only file).
