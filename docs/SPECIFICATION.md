# Specification: Markdown Review

This is the specification of the plugin as shipped. The plans ([PHASE3.md](PHASE3.md), [PHASE4.md](PHASE4.md)), the
code and the [engine contract](ENGINE_CONTRACT.md) refer to its requirement IDs (FR-*, NFR-*). Milestone IDs such as
M1, P2.5 or P4.3 come from the phase plans; completed plans remain in the git history.

The original specification was written before the repository and was never committed. This file restores it from
the IDs and descriptions used in the repository and updates it to the released product; requirements added after
1.0 keep the milestone that shipped them. IDs that nothing in the repository mentions (FR-5, FR-8, FR-10, NFR-1,
NFR-3, NFR-9) are not restored, so their text is not guessed; the numbering is kept so existing references stay
valid.

## Purpose

Review changes to Markdown and AsciiDoc files in rendered form inside IntelliJ IDEA, instead of reading a text diff
of the markup. The plugin adds viewers to IntelliJ's own diff window; it has no window of its own. Markdown needs
only the plugin; AsciiDoc diffs also need the JetBrains AsciiDoc plugin, which supplies AsciidoctorJ (NFR-11).

## Functional requirements

| ID | Requirement | Status |
|---|---|---|
| FR-1 | Side-by-side comparison of the rendered old and new versions. | Shipped |
| FR-2 | Blocks (headings, paragraphs, list items, table rows, code lines) aligned in rows across both sides; a side without a block keeps an empty placeholder of the same height. | Shipped |
| FR-3 | Removed blocks on a red background, added blocks on green, changed words highlighted inside a modified block. | Shipped |
| FR-4 | Merged view: one column, removed text red and struck through, added text green. | Shipped |
| FR-6 | Navigation between changes (F7 / Shift+F7) and a change counter. | Shipped |
| FR-7 | Ignore whitespace and source-only reflow, such as a re-wrapped paragraph. | Shipped (diff window settings menu) |
| FR-9 | Compare any two Markdown or AsciiDoc files, not only VCS revisions (**Compare Rendered Files...**). | Shipped (P4.6) |
| FR-11 | Settings page under Settings \| Tools \| Markdown Review, including the default viewer for Markdown and AsciiDoc diffs (rendered side by side, rendered merged, or text). | Shipped, see [ADR 0001](adr/0001-default-diff-viewer.md) |
| FR-12 | Revert and apply a change per block, like the arrows in the text diff, through the IDE document so Undo works. | Shipped (P2.5) |
| FR-13 | Edit a block's raw source in place (double-click). | Shipped (P2.6) |
| FR-14 | Ctrl/Cmd+click on a block opens its source line in the editor. | Shipped |
| FR-15 | Detect moved blocks and show them as moved rather than removed and added. | Shipped (P2.7) |
| FR-16 | AsciiDoc files (`.adoc`, `.asciidoc` and any file of the AsciiDoc plugin's file type) open in the rendered viewers wherever Markdown files do: the Commit window, Git log, file history, branch comparison, pull request review, **Show Rendered Diff** and **Compare Rendered Files...**. | Shipped (P4.6) |
| FR-17 | The block diff of FR-1 to FR-4, FR-6 and FR-7 for AsciiDoc: section titles, paragraphs, list items (unordered, ordered, checklists, description lists, callout lists), table rows, lines of listing and literal blocks, admonitions, block titles, images, thematic and page breaks, and the document header are aligned in rows, with word diffs in modified blocks. | Shipped (P4.3, P4.4) |
| FR-18 | Changes the visible text does not show are marked, as for Markdown: a changed section level, admonition type, block title, source language, link or image target, cross-reference target, checklist state, or an attribute entry. | Shipped (P4.4) |
| FR-19 | Delimited blocks keep their structure: example, sidebar, quote, verse, open and admonition blocks frame the blocks inside them like a Markdown blockquote or alert does, on both sides of the diff. | Shipped (P4.4) |
| FR-20 | Per-block revert and apply (FR-12), block source editing (FR-13), Ctrl/Cmd+click to source (FR-14), moved blocks (FR-15) and the block menu (P3.4) work on AsciiDoc blocks, through the IDE document, with one Undo step per edit. | Shipped (P4.5, P4.6) |
| FR-21 | HTML and PDF export, multi-file export and the custom stylesheet (P3.6) work for AsciiDoc diffs; a multi-file export may mix Markdown and AsciiDoc files. | Shipped (P4.6) |
| FR-22 | `[mermaid]` listing blocks are drawn with the bundled Mermaid and `stem:[...]` / `latexmath:[...]` / `[stem]` blocks with the bundled KaTeX, with the same toggles and source diff as Markdown diagrams and formulas. | Shipped (P4.7) |
| FR-23 | Document attributes defined in the file's own header or body are applied, so `{product-version}` renders as its value and changing an attribute entry shows as a change on the entry and on every block whose text it changes. `include::` directives are not followed; each is shown as its own block with the target, and a changed target is a change. | Shipped (P4.3) |
| FR-24 | AsciiDoc comments (`//` and `////`) are hidden unless they changed, like HTML comments in Markdown. | Shipped (P4.3) |

The text diff is IntelliJ's built-in viewer, one click away in the diff window's viewer switcher.

### Settings (FR-11)

- Default viewer for Markdown and AsciiDoc diffs.
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

### Supported AsciiDoc

AsciiDoc through AsciidoctorJ, taken from the JetBrains AsciiDoc plugin and not bundled (NFR-11, NFR-13):

- Sections and the document title, including discrete headings; a changed section level is marked.
- Paragraphs, including literal paragraphs; inline formatting, links (`link:`), cross-references (`xref:` and
  `<<id>>`) and Asciidoctor's menu and keyboard macros are converted by Asciidoctor and sanitized like Markdown HTML.
- Lists: unordered, ordered (including `start`), checklists and their state, description lists, callout lists, nested
  lists and blocks attached with `+` continuations.
- Tables with header rows, column spans and `a|` cells; an `a|` cell is compared by its text, not block by block.
- Listing, source and literal blocks (`----`, `....`), with the source language, callouts and one diff row per line.
- Admonitions in both forms: `NOTE:` paragraphs and `[NOTE]` / `====` blocks. A changed type is marked.
- Delimited containers: example (`====`), sidebar (`****`), quote and verse (`____`) and open (`--`) blocks, nested,
  drawn as frames around their blocks.
- Attribute entries in the document header and body; references are substituted, so `{product-version}` renders as
  its value and a changed entry marks the entry and the blocks whose text changes.
- Line (`//`) and block (`////`) comments, hidden unless changed.
- `include::target[]` directives: each is a block with a link to its target, never resolved; a changed target is a
  change.
- Conditional directives (`ifdef`, `ifndef`, `ifeval`, `endif`) evaluated with the document's own attributes; content
  they exclude is not shown, and a changed directive is a hidden-unless-changed row.
- Block images (`image::target[alt]`) and inline images, resolved against the side's directory; changed targets and
  alt text are marked. Block titles (`.Title`) are blocks of their own, before the block they title.
- Thematic breaks (`'''`) and page breaks (`<<<`); the kind of break is compared.
- Mermaid: a `[mermaid]` listing or literal block is drawn with the bundled Mermaid, with the same source diff as a
  Markdown diagram (FR-22).
- Math: `latexmath:[...]` inline formulas, `[latexmath]` blocks, and `stem:[...]` / `[stem]` when `stem` is
  `latexmath`, are drawn with the bundled KaTeX as Markdown formulas are. AsciiMath is not drawn.

Not supported in AsciiDoc diffs: resolved `include::` files, Asciidoctor extensions other than Mermaid
(asciidoctor-diagram's PlantUML, Graphviz, Kroki and similar styles stay code blocks), AsciiMath, Antora
cross-component links, and Asciidoctor PDF themes. Comparing a Markdown file with an AsciiDoc file (for example a
rename from `README.md` to `README.adoc`) keeps IntelliJ's text diff; both sides of a rendered diff have one format.

## Non-functional requirements

| ID | Requirement | Status |
|---|---|---|
| NFR-2 | Performance: a 1,000-line document renders in under 300 ms and a 10,000-line one in under 2 s, off the EDT on a typical laptop. A benchmark runs in CI and reports, without gating the build. | Shipped; see NFR-12 for AsciiDoc |
| NFR-4 | Large files: above a limit (default 20,000 lines or 2 MB per side, set in settings) the viewer shows a notice with an **Open Text Viewer** button instead of rendering. | Shipped |
| NFR-5 | Colors and fonts follow the current editor color scheme, light or dark. | Shipped |
| NFR-6 | Without JCEF the plugin stays out of the way: the rendered viewers do not offer themselves and the text diff stays. | Shipped |
| NFR-7 | Every edit made from the rendered view (FR-12) is one Undo step in the IDE document, and a stale view refuses the edit and re-renders. | Shipped |
| NFR-8 | Security and privacy: no network access unless remote images are enabled; raw HTML is sanitized (no scripts, event handlers or `javascript:` URLs); the page's Content Security Policy allows only the plugin's own scripts. | Shipped |
| NFR-10 | The user interface is in English only. | Shipped |
| NFR-11 | AsciiDoc support is active only while the JetBrains AsciiDoc plugin (`org.asciidoctor.intellij.asciidoc`) is installed and enabled ([ADR 0003](adr/0003-asciidoctorj-from-asciidoc-plugin.md)). Without it, `.adoc` diffs keep the text viewer and nothing fails or logs errors; the settings page says what to install. | Shipped (P4.6) |
| NFR-12 | Performance: after the first AsciiDoc render in an IDE session, the NFR-2 budget applies to AsciiDoc too (1,000 lines under 300 ms, 10,000 lines under 2 s). The first render, which starts Asciidoctor, runs off the EDT with a "Preparing AsciiDoc renderer..." notice and takes under 5 s on a typical laptop. | Shipped (P4.6); P4.1 measured the budget, see below |
| NFR-13 | Security: Asciidoctor runs in `SECURE` safe mode, reads no files and no network, and its output goes through the same HTML allowlist as Markdown (NFR-8). The page's CSP does not change. | Shipped (P4.3) |
| NFR-14 | Markdown is unchanged: every existing fixture and test passes without edits to its expectations, and the Markdown benchmark stays within 5% of 1.3.0. The plugin zip grows by no more than 1 MB. | Shipped (P4.2, P4.6); measured, see below |
| NFR-15 | The core stays free of IntelliJ Platform APIs (ENGINE_CONTRACT): the AsciiDoc front end depends on AsciidoctorJ only, and is unit-tested with AsciidoctorJ on the test classpath. | Shipped (P4.3) |

Measured for NFR-12 in P4.1 (Apple Silicon, Oracle JDK 21.0.9, 2 GB heap): the first
`Asciidoctor.Factory.create()` took 1.57–2.14 s and the first 1,015-line load 236–266 ms, so the first
render is about 1.8–2.4 s. Steady state, median of 10: 1,015 lines 60–65 ms and 10,003 lines
138–295 ms for load plus all inline conversion. The budget holds; NFR-12 stays as written.

Measured for NFR-14: P4.2 passed every existing Markdown test and fixture unchanged, with the benchmark at
8 ms / 6 ms for 1,000 lines and 90 ms / 79 ms for 10,000 lines (side by side / merged). P4.6 reports the plugin zip
at 2.2 MB with no AsciidoctorJ or Ruby classes inside, and `verifyPlugin` compatible on IC 2025.1.3 and IU 2026.1
(IU 263 reports only the baseline VCS compatibility problems).

## Platform

IntelliJ IDEA Community or Ultimate 2025.1 or newer (build 251+), with JCEF (the default in JetBrains Runtime).
Distributed through JetBrains Marketplace; see [RELEASING.md](RELEASING.md).

AsciiDoc diffs additionally need the JetBrains AsciiDoc plugin (`org.asciidoctor.intellij.asciidoc`) installed and
enabled. Markdown Review takes AsciidoctorJ from that plugin through an optional dependency and does not bundle it
([ADR 0003](adr/0003-asciidoctorj-from-asciidoc-plugin.md)); without the plugin, AsciiDoc diffs keep the text viewer
and Markdown diffs are unaffected.

## License

[PolyForm Noncommercial License 1.0.0](../LICENSE): free for noncommercial use; commercial use needs a separate
license from the author, offered through a GitHub Sponsors commercial tier (P3.7). Bundled third-party components keep
their own licenses ([THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md); Mermaid and KaTeX are MIT).

## Out of scope

WYSIWYG editing (HTML back to source normalizes the file and clutters the diff), three-way merge, other markup
formats (reStructuredText), resolved AsciiDoc includes, Asciidoctor extensions other than Mermaid, AsciiMath,
Antora cross-component links, Asciidoctor PDF themes, comparing a Markdown file with an AsciiDoc file, and a paid
tier inside the plugin. AsciiDoc is supported from phase 4 (FR-16 to FR-24); reStructuredText is not.

## Answered open questions

- **License**: PolyForm Noncommercial 1.0.0, not MIT or Apache-2.0. Commercial use through GitHub Sponsors.
- **UI language**: English only (NFR-10); no Polish translation.
- **Default viewer**: the plugin's viewers come first in the diff window's tool order, and the setting rewrites the
  remembered order per kind of diff window; `DiffToolSubstitutor` was rejected
  ([ADR 0001](adr/0001-default-diff-viewer.md)).
- **Mermaid and math**: Mermaid moved from out of scope into 1.0; math rendering followed after 1.0. AsciiDoc
  Mermaid blocks and `latexmath` formulas followed in P4.7; AsciiMath stays out.
- **AsciiDoc**: out of scope for 1.0 and kept out of phase 3, then supported in phase 4. ADR 0003 takes AsciidoctorJ
  from the AsciiDoc plugin instead of bundling it, which keeps Markdown's download unchanged (NFR-11, NFR-14).
  Resolved includes, extensions other than Mermaid, AsciiMath and mixed-format pairs remain out of scope.
- **Block source editing and moved blocks** (FR-13, FR-15): deferred past 1.0 to P3.2; both followed after 1.0.
- **Pairing of rewritten blocks**: a user setting, default 0.5, range 0.2 to 0.8.
- **Renderer**: commonmark-java for Markdown and AsciidoctorJ for AsciiDoc; JCEF for display; diff on the block model
  rather than on the finished HTML.
- **One engine for both formats**: a format-neutral block engine and HTML generator, with a front end per format
  ([ADR 0004](adr/0004-format-neutral-engine.md)); AsciiDoc reuses the page, styles, Mermaid, KaTeX and export
  ([ADR 0006](adr/0006-asciidoc-rendering-reuse.md)).
