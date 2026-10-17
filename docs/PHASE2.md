# Phase 2 plan

Phase 1 (commit `0b633b5`) delivered milestones M1–M4 of [PLAN.md](../PLAN.md) in their core form: the diff engine,
the HTML generator, two JCEF viewers registered as `diff.DiffTool`, change navigation, the whitespace and collapse
toggles, Ctrl/Cmd+click to source, and **Compare Markdown Files...**. 57 tests pass and `verifyPlugin` reports the
plugin compatible with IC 2025.1.3.

Phase 2 takes the plugin from a working build to a 1.0 release on JetBrains Marketplace. It finishes what phase 1
left open in M4 and M5, adds the M6 block actions, and closes a few engine gaps found in the fixtures.

Requirement IDs (FR-*, NFR-*) refer to the specification.

## Overview

| Milestone | Theme | Depends on | Release |
|-----------|-------|------------|---------|
| P2.1 | Repository, CI and build upkeep | none | |
| P2.2 | IDE integration: settings, default viewer, integration tests | P2.1 | 0.2 (beta) |
| P2.3 | Engine refinements: links, heading levels, images, large files | P2.1 | 0.2 (beta) |
| P2.4 | Markdown element coverage: GFM first, then HTML, math, emoji | P2.1 | GFM in 0.2 (beta), the rest in 1.0 |
| P2.5 | Per-block revert and apply | P2.2, P2.3 | |
| P2.6 | Block source editing | P2.5 | |
| P2.7 | Moved-block detection | P2.3 | after 1.0 by default |
| P2.8 | Mermaid diagrams and math rendering | P2.2, P2.4 | 1.0 |
| P2.9 | Release 1.0 | P2.4, P2.5, P2.8 (P2.6 if kept in 1.0) | 1.0 |

P2.2, P2.3 and P2.4 are independent and can run in parallel; P2.4 should follow P2.3 into `BlockExtractor` to
avoid conflicts if one person does both. P2.5 waits for P2.2 and P2.3: it needs the settings page for its toggle
and the final shape of `BlockInfo` from the engine work. P2.8 builds on P2.4's math blocks and can run in parallel
with P2.5.

## P2.1 Repository, CI and build upkeep

Scope:
- Push `main` to `github.com/agadek/markdown-review` (the remote is configured; the GitHub repository is empty).
- Add `.github/workflows/build.yml`: JDK 21, Gradle cache, `./gradlew test buildPlugin verifyPlugin` on push and
  pull request, test reports and the plugin ZIP uploaded as artifacts.
- Bump the IntelliJ Platform Gradle Plugin from 2.5.0 to the latest 2.x release and fix any DSL changes.
- Widen `pluginVerification` to the current stable IDE releases (IC and IU) on top of 2025.1.3, so a missing
  `untilBuild` or a removed API shows up in CI rather than in a user report.
- Keep the Kotlin and commonmark versions current in the same pass.

Done when:
- CI is green on `main` and runs on every pull request.
- `./gradlew test buildPlugin verifyPlugin` passes locally with the new Gradle plugin, with no deprecation warnings
  from it.
- `verifyPlugin` reports "Compatible" for every IDE in the list.

## P2.2 IDE integration

Scope:
- **Settings page** (FR-11): `applicationConfigurable` under Tools, backed by a `PersistentStateComponent` that
  replaces the `PropertiesComponent` keys in `MarkdownReviewSettings` (migrating their current values). Options:
  default viewer for `.md` (rendered side by side, rendered merged, text), ignore whitespace, collapse unchanged
  blocks, large-file threshold (P2.3), load remote images (NFR-8), and the pairing threshold: the word similarity
  (default 0.5) at which a removed and an added block count as one modified block. The pairing threshold was added
  after the rest of P2.2 merged and ships in 1.0; changing it re-renders open diffs, with fixtures at 0.3 and 0.7.
- **Default viewer for `.md`**: verify in the 2025.1 API how a `DiffTool` becomes the preselected viewer. Candidates,
  in order: `DiffToolSubstitutor`, then the diff window's remembered viewer choice (`DiffSettings` / `DiffUtil` tool
  order). Record the result in `docs/ENGINE_CONTRACT.md` or a short ADR. Whatever the mechanism, the user must be able
  to switch back to the text viewer, and the setting must control it.
- **Optional Markdown plugin dependency**: `<depends optional="true">org.intellij.plugins.markdown</depends>` only
  to recognize the Markdown file type for files without a `.md` extension. Rendering stays on commonmark.
- **Integration tests** (planned for M4, not yet written): `BasePlatformTestCase` tests for `canShow()` (Markdown vs
  other types, `EmptyContent` on one side, two documents), viewer creation from a `SimpleDiffRequest`, the settings
  round trip, and **Compare Markdown Files...** with two, one and zero selected files.
- Manual check of S1–S4 in `runIde`: commit window, branch compare, file history, two arbitrary files.

Done when:
- The settings page exists, its values survive an IDE restart, and the old toggle values are migrated.
- With the default set to rendered, opening the diff of a `.md` file in the commit window shows the rendered viewer
  first; with it set to text, IntelliJ's text viewer comes first.
- The integration tests run in CI.

## P2.3 Engine refinements

Scope:
- **Link URL changes** (fixture 09): a link whose text is equal but whose `href` changed makes its block `MODIFIED`,
  and the renderer marks the link (CSS class plus a `title` with the old URL). The same for image `src`. A changed
  reference definition (`[mp]: url`) marks every paragraph that uses that label.
- **Heading level only** (fixture 05): a heading whose text is equal but whose level changed gets a visible note
  (for example "h3 → h4") instead of an empty word diff.
- **Relative images**: resolve local image paths against each side's file. This needs a new optional field in
  `RenderRequest` (base URI per side); the IDE fills it from the `DocumentContent`'s file, and `null` keeps today's
  behavior. Remote images stay replaced by a placeholder unless the setting allows them (NFR-8).
- **Large files** (NFR-4): above the threshold (default 20,000 lines or 2 MB) the viewer shows a message and a
  button to open the text viewer instead of rendering.
- **Performance check** (NFR-2): a benchmark test on generated 1,000- and 10,000-line files, reported in CI, not
  gating the build.
- Update `docs/ENGINE_CONTRACT.md` and the fixture `EXPECTED.md` files for every behavior change, and pin each one in
  `FixturesTest`.

Done when:
- Fixtures 05 and 09 assert the new behavior, including the reference-definition case.
- An image next to the compared file renders in both viewers.
- A file above the threshold opens with the message, not a frozen panel.
- 1,000 lines render in under 300 ms and 10,000 lines in under 2 s off the EDT on a typical laptop.

## P2.4 Markdown element coverage

Today the parser enables three commonmark-java extensions: tables, strikethrough and task list items
(`BlockExtractor.extensions`). Core commonmark also gives HTML blocks, inline HTML, `<url>` autolinks, thematic
breaks and hard line breaks. Footnotes, bare-URL autolinks, GitHub alerts, math and emoji shortcodes are not
recognized and show as plain text. Fixtures 01–20 cover paragraphs, headings, nested lists, tables, code, links and
images, blockquotes and front matter, but none of the elements below.

Every element gets its own fixture pair under `src/test/resources/fixtures/` (numbered from 21, same
`old.md` / `new.md` / `EXPECTED.md` layout) with an assertion in `FixturesTest` and, where the rendering is new, a
`DiffHtmlRendererTest` case.

### Wave A: GitHub Flavored Markdown (0.2 beta)

GFM first, because it is the format of README files on GitHub, where most reviewed Markdown lives.

- **Task lists** (`- [ ]`, `- [x]`): already parsed. Add a fixture where only the checkbox state changes; that must
  be a `MODIFIED` list item with a visible checkbox change, not an empty word diff.
- **Strikethrough** (`~~text~~`): already parsed. Fixture for adding and removing strikethrough on unchanged words,
  so the formatting change is visible next to the diff's own `<del>` styling (use a distinct style for content
  strikethrough).
- **Autolinks**: add `commonmark-ext-autolink` so bare `https://...` and `www.` URLs become links, as on GitHub.
  Fixture with a bare URL that changes.
- **Footnotes** (`[^1]` and `[^1]: text`): add `commonmark-ext-footnotes`. A footnote definition is its own block;
  a changed definition marks the definition, and the reference in the text links to it within the page. Fixture
  with an added, a removed and a changed footnote.
- **GitHub alerts** (`> [!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]`, `[!CAUTION]`): use commonmark-java's
  alerts extension if the version in use has one, otherwise a post-processor that turns a blockquote starting with
  the marker into an alert node. Rendered with the type's title and color from the theme variables. Changing only
  the alert type is `MODIFIED` with a note, like the heading level change in P2.3.

### Wave B: HTML and other popular syntax (1.0)

- **HTML blocks and inline HTML**: already parsed and sanitized (`HtmlSanitizer`). Add fixtures for a changed HTML
  block, inline `<kbd>` / `<sub>` / `<sup>`, and dangerous input (`<script>`, `on*` attributes, `javascript:` URLs)
  to pin NFR-8.
- **`<details>` / `<summary>`**: render as a real collapsible element that starts open when it contains a change.
  Fixture with a change inside a `<details>` block.
- **HTML comments** (`<!-- -->`): not visible when rendered, so a change in a comment shows as a muted
  "comment changed" row with the comment text, instead of an invisible change. Fixture with an added and an edited
  comment.
- **Hard line breaks** (two trailing spaces or `\`): today they count as a space in the compared text, so adding or
  removing one is not detected. Per the specification a hard break is a change; make it part of the block key and
  show a break marker in the word diff. Fixture for both syntaxes.
- **Thematic breaks** (`---`, `***`, `___`): already a block kind. Fixture for an added and a removed break, and
  for a syntax-only change (`---` to `***`), which stays equal.
- **Math** (`$...$`, `$$...$$`): no commonmark-java extension exists, so add a small inline and block parser
  extension that makes formulas nodes of their own, compared as units and shown as monospace source. Drawing them
  is part of P2.8. Fixture with inline and block formulas, one changed.
- **Emoji shortcodes** (`:smile:`): convert GitHub shortcodes to Unicode emoji from a bundled table, so a changed
  shortcode shows as a changed emoji. Unknown shortcodes stay as text. Fixture with known and unknown shortcodes.

Done when:
- Wave A: fixtures for task lists, strikethrough, autolinks, footnotes and alerts pass, and a typical GitHub README
  using them renders in both viewers as it does on GitHub.
- Wave B: fixtures for HTML blocks, inline HTML, `<details>`, comments, hard line breaks, thematic breaks, math and
  emoji pass, including the sanitizer cases.
- `docs/ENGINE_CONTRACT.md` lists the supported elements and any new block kinds.

## P2.5 Per-block revert and apply (FR-12, NFR-7)

Scope:
- Extend `BlockInfo` with what the IDE needs to edit the target document: for `ADDED` and `REMOVED` blocks, the
  insertion line on the other side (after the previous matched block). This is an additive change to the contract.
- Hover buttons in the side-by-side view: ← revert (right to left) and → apply (left to right), shown only when the
  target side has a writable `DocumentContent`. The merged view gets a revert button only.
- The click goes through `JBCefJSQuery` as `{action, idx}`; Kotlin replaces exactly the block's line range in the
  target `Document` inside `WriteCommandAction` named "Revert Markdown Block", so it is one Undo step.
- Guard against stale views: the request carries the document's `modificationStamp` at render time; a mismatch
  refuses the action and re-renders.
- Keep the scroll position after the re-render (already done for edits; verify for block actions).
- Tests on a real `Document`: replace, insert, delete at the start, middle and end; CRLF files; a single Undo
  restores the original; a stale stamp is refused.

Done when:
- Reverting every change in fixture 18 one by one gives a right side identical to the left.
- Each action is one Undo step, and Redo works.
- Buttons never appear on read-only sides (history, branch compare, PR windows).

## P2.6 Block source editing (FR-13)

Scope:
- Double-click on a block on a writable side replaces it with a `<textarea>` holding the block's raw Markdown.
  Ctrl/Cmd+Enter saves through the same write path as P2.5, Esc cancels.
- The textarea keeps its content and caret while the document re-renders from other sources (or the re-render is
  paused while editing).

Done when:
- Editing a paragraph, a table row and a list item saves the expected source, as one Undo step.
- Typing in the textarea never loses input to a re-render.

## P2.7 Moved-block detection (FR-15)

Scope:
- After pairing, `REMOVED` and `ADDED` blocks with equal keys in different windows become a moved pair. Add a
  `movedFrom` / `movedTo` index to `BlockInfo` rather than a new `BlockStatus`, so the IDE side and P2.5 keep
  working unchanged.
- The renderer labels both ends ("moved from / to" with a link to the other end); no word diff.
- Revert of a moved block moves it back (two edits in one command).

Done when:
- Fixture 04 reports one moved section instead of two removals and two additions, and no block is `MODIFIED`.
- Fixture 20 (large file) keeps its current counts or the changes are explained in its `EXPECTED.md`.

## P2.8 Mermaid diagrams and math rendering

Today a ` ```mermaid ` block is a plain code block: the engine has no Mermaid handling, splits the fence into a fence
block plus one block per source line, and the renderer shows the changed lines with a word diff.

Scope:
- **Engine**: keep a `mermaid` fence as one block (its whole source as `rawText`) instead of one block per line, so a
  diagram is compared and rendered as a unit. Equal source means equal block; any change makes it `MODIFIED`.
- **Renderer**: emit the diagram source in a `<pre class="mermaid">` (or similar inert element) per side, with no
  scripts in `bodyHtml`, as the contract requires. A changed diagram shows before and after side by side in the
  side-by-side view and stacked (old struck through, new below) in the merged view.
- **Source diff stays available**: each changed diagram has a toggle ("Show source changes") that swaps the drawings
  for today's line-by-line code diff. The fixture case for this also covers a syntax error on one side, which must
  show Mermaid's error text, not a blank row.
- **IDE**: bundle a pinned copy of `mermaid.js` as a plugin resource (offline, no network), load it in the JCEF page
  with the page's CSP nonce, and run it after each render. Re-render on theme change with Mermaid's dark or default
  theme to match the editor scheme. Check the effect on `HtmlPage`'s CSP (`style-src 'unsafe-inline'` is already
  allowed; nothing else should need loosening) and record the Mermaid version and license (MIT) in the README.
- **Settings**: a "Render Mermaid diagrams" option on the P2.2 settings page, on by default; off keeps today's code view.
- **Large diagrams**: rendering is client-side and asynchronous, so a slow diagram must not block scrolling or change
  navigation; row heights settle after each diagram is drawn.

- **Math**: P2.4 already makes `$...$` and `$$...$$` separate inline and block nodes shown as monospace source.
  Here they are drawn with a bundled, pinned copy of KaTeX (MIT), loaded the same way as `mermaid.js`, with the same
  setting pattern ("Render math", on by default) and the same "Show source changes" toggle for changed formulas. A
  formula KaTeX cannot parse shows its source with an error marker.

Done when:
- A new fixture with an added, a removed, a changed and an unchanged Mermaid diagram shows each with the right status,
  and the changed one shows both drawings side by side.
- The toggle shows the source line diff for a changed diagram.
- The plugin renders diagrams with the network disabled, and `verifyPlugin` still passes.
- With the setting off, Mermaid blocks look exactly as they do today.
- The math fixture from P2.4 renders its formulas with KaTeX, offline, and an invalid formula shows its source.

## P2.9 Release 1.0

Scope:
- Marketplace listing: description, change notes, plugin icon (`pluginIcon.svg`, light and dark), screenshots of
  both viewers.
- `signPlugin` and `publishPlugin` configured with secrets in CI; the first upload done by hand for moderation.
- README: remove "planned" from the features that shipped, add screenshots and the settings page.
- Update the specification so it matches the shipped product: English-only UI (NFR-10 currently says Polish is
  optional), PolyForm Noncommercial license (the open questions still list MIT or Apache-2.0), Mermaid and math rendering
  moved from out of scope into 1.0, the supported Markdown elements from P2.4, and the answered open questions.

Done when:
- The plugin is approved on JetBrains Marketplace and installs from it into a clean IDE.
- CI publishes a signed build from a tag.

## Not in phase 2

WYSIWYG editing, three-way merge, other formats (AsciiDoc, reStructuredText), a paid tier. These stay as listed in
the specification's out-of-scope section. Mermaid and LaTeX math were on that list too and are now P2.8.
