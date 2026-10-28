# Specification: Markdown Review 1.0

This is the specification of the plugin as shipped in 1.0. The plans ([PLAN.md](../PLAN.md),
[PHASE2.md](PHASE2.md), [PHASE3.md](PHASE3.md)), the code and the [engine contract](ENGINE_CONTRACT.md) refer to its
requirement IDs (FR-*, NFR-*).

The original specification was written before the repository and was never committed. This file restores it from
the IDs and descriptions used in the repository and updates it to the 1.0 product, as P2.9 asks. IDs that nothing in
the repository mentions (FR-5, FR-8, FR-10, NFR-1, NFR-3, NFR-9) are not restored, so their text is not guessed;
the numbering is kept so existing references stay valid.

## Purpose

Review changes to Markdown files in rendered form inside IntelliJ IDEA, instead of reading a text diff of the markup.
The plugin adds viewers to IntelliJ's own diff window; it has no window of its own.

## Functional requirements

| ID | Requirement | 1.0 |
|---|---|---|
| FR-1 | Side-by-side comparison of the rendered old and new versions. | Shipped |
| FR-2 | Blocks (headings, paragraphs, list items, table rows, code lines) aligned in rows across both sides; a side without a block keeps an empty placeholder of the same height. | Shipped |
| FR-3 | Removed blocks on a red background, added blocks on green, changed words highlighted inside a modified block. | Shipped |
| FR-4 | Merged view: one column, removed text red and struck through, added text green. | Shipped |
| FR-6 | Navigation between changes (F7 / Shift+F7) and a change counter. | Shipped |
| FR-7 | Ignore whitespace and source-only reflow, such as a re-wrapped paragraph. | Shipped (diff window settings menu) |
| FR-9 | Compare any two Markdown files, not only VCS revisions (**Compare Markdown Files...**). | Shipped |
| FR-11 | Settings page under Settings \| Tools \| Markdown Review, including the default viewer for Markdown diffs (rendered side by side, rendered merged, or text). | Shipped, see [ADR 0001](adr/0001-default-diff-viewer.md) |
| FR-12 | Revert and apply a change per block, like the arrows in the text diff, through the IDE document so Undo works. | Shipped (P2.5) |
| FR-13 | Edit a block's raw Markdown in place (double-click). | Shipped (P2.6) |
| FR-14 | Ctrl/Cmd+click on a block opens its source line in the editor. | Shipped |
| FR-15 | Detect moved blocks and show them as moved rather than removed and added. | Shipped (P2.7) |

The text diff is IntelliJ's built-in viewer, one click away in the diff window's viewer switcher.

### Settings (FR-11)

- Default viewer for Markdown diffs.
- Ignore whitespace and line wrapping; collapse unchanged blocks.
- Pairing threshold: the word similarity at which a removed and an added block show as one modified block with a
  word diff. Default 0.5, adjustable from 0.2 to 0.8; changing it re-renders open diffs.
- Large-file limits (NFR-4).
- Load remote images (NFR-8), off by default.
- Render Mermaid diagrams, on by default.
- Render math, on by default.
- Show revert and apply buttons on changed blocks (FR-12).
- Support links to GitHub Sponsors and Ko-fi, at the bottom of the page.

### Supported Markdown

CommonMark through commonmark-java, with:

- GitHub Flavored Markdown: tables, task lists, strikethrough, autolinks (bare URLs and `www.`), footnotes and
  GitHub alerts (`> [!NOTE]` and the other four types).
- HTML blocks and inline HTML, sanitized by an allowlist (NFR-8); `<details>` / `<summary>` as a collapsible
  section that opens when it contains a change; HTML comments, hidden unless changed.
- Hard line breaks (two trailing spaces or `\`) as part of the compared text; thematic breaks compared by kind.
- Emoji shortcodes (`:smile:`) converted to Unicode emoji; unknown shortcodes stay as text.
- Wiki links (`[[page]]`, `[[page|label]]`, `[[page#section]]`, target first as in Obsidian, Foam and MediaWiki) as
  links: a changed label is a word change, a changed target is marked like any changed link.
- YAML front matter, compared line by line.
- Relative images resolved against each side's file.
- Mermaid diagrams (` ```mermaid `): compared as one block and drawn with a bundled, pinned Mermaid library, offline,
  in the editor's light or dark theme. A changed diagram shows both drawings, with a "Show source changes" toggle for
  the line diff of its source; a diagram that does not parse shows Mermaid's error and its source.
- Math (`$...$`, `$$...$$`): formulas are compared as units and drawn with a bundled, pinned KaTeX,
  offline. A changed inline formula is highlighted as a whole; a changed block formula shows both drawings with a
  "Show source changes" toggle. A formula that does not parse keeps its source with KaTeX's error. Prices such as
  "$5 or $10" are not formulas, as on GitHub.

A changed link target, image source, heading level or alert type is marked as a change even when the visible text is
equal.

## Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-2 | Performance: a 1,000-line document renders in under 300 ms and a 10,000-line one in under 2 s, off the EDT on a typical laptop. A benchmark runs in CI and reports, without gating the build. |
| NFR-4 | Large files: above a limit (default 20,000 lines or 2 MB per side, set in settings) the viewer shows a notice with an **Open Text Viewer** button instead of rendering. |
| NFR-5 | Colors and fonts follow the current editor color scheme, light or dark. |
| NFR-6 | Without JCEF the plugin stays out of the way: the rendered viewers do not offer themselves and the text diff stays. |
| NFR-7 | Every edit made from the rendered view (FR-12) is one Undo step in the IDE document, and a stale view refuses the edit and re-renders. |
| NFR-8 | Security and privacy: no network access unless remote images are enabled; raw HTML is sanitized (no scripts, event handlers or `javascript:` URLs); the page's Content Security Policy allows only the plugin's own scripts. |
| NFR-10 | The user interface is in English only. |

## Platform

IntelliJ IDEA Community or Ultimate 2025.1 or newer (build 251+), with JCEF (the default in JetBrains Runtime).
Distributed through JetBrains Marketplace; see [RELEASING.md](RELEASING.md).

## License

[PolyForm Noncommercial License 1.0.0](../LICENSE): free for noncommercial use; commercial use needs a separate
license from the author, offered through a GitHub Sponsors commercial tier (P3.7). Bundled third-party components keep
their own licenses ([THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md); Mermaid and KaTeX are MIT).

## Out of scope

WYSIWYG editing (HTML back to Markdown normalizes the file and clutters the diff), three-way merge, other markup
formats (AsciiDoc, reStructuredText) and a paid tier inside the plugin. Mermaid diagrams and math, which were on this
list, are now supported.

## Answered open questions

- **License**: PolyForm Noncommercial 1.0.0, not MIT or Apache-2.0. Commercial use through GitHub Sponsors.
- **UI language**: English only (NFR-10); no Polish translation.
- **Default viewer for `.md`**: the plugin's viewers come first in the diff window's tool order, and the setting
  rewrites the remembered order per kind of diff window; `DiffToolSubstitutor` was rejected
  ([ADR 0001](adr/0001-default-diff-viewer.md)).
- **Mermaid and math**: Mermaid moved from out of scope into 1.0; math rendering followed after 1.0.
- **Block source editing and moved blocks** (FR-13, FR-15): deferred past 1.0 to P3.2; both followed after 1.0.
- **Pairing of rewritten blocks**: a user setting, default 0.5, range 0.2 to 0.8.
- **Renderer**: commonmark-java for parsing, JCEF for display, diff on the AST rather than on the finished HTML.
