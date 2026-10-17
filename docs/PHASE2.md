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
| P2.4 | Per-block revert and apply | P2.2, P2.3 | |
| P2.5 | Block source editing | P2.4 | |
| P2.6 | Moved-block detection | P2.3 | after 1.0 by default |
| P2.7 | Release 1.0 | P2.4 (P2.5 if kept in 1.0) | 1.0 |

P2.2 and P2.3 are independent and can run in parallel. P2.4 waits for both: it needs the settings page for its
toggle and the final shape of `BlockInfo` from the engine work.

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
  blocks, large-file threshold (P2.3), load remote images (NFR-8).
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

## P2.4 Per-block revert and apply (FR-12, NFR-7)

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

## P2.5 Block source editing (FR-13)

Scope:
- Double-click on a block on a writable side replaces it with a `<textarea>` holding the block's raw Markdown.
  Ctrl/Cmd+Enter saves through the same write path as P2.4, Esc cancels.
- The textarea keeps its content and caret while the document re-renders from other sources (or the re-render is
  paused while editing).

Done when:
- Editing a paragraph, a table row and a list item saves the expected source, as one Undo step.
- Typing in the textarea never loses input to a re-render.

## P2.6 Moved-block detection (FR-15)

Scope:
- After pairing, `REMOVED` and `ADDED` blocks with equal keys in different windows become a moved pair. Add a
  `movedFrom` / `movedTo` index to `BlockInfo` rather than a new `BlockStatus`, so the IDE side and P2.4 keep
  working unchanged.
- The renderer labels both ends ("moved from / to" with a link to the other end); no word diff.
- Revert of a moved block moves it back (two edits in one command).

Done when:
- Fixture 04 reports one moved section instead of two removals and two additions, and no block is `MODIFIED`.
- Fixture 20 (large file) keeps its current counts or the changes are explained in its `EXPECTED.md`.

## P2.7 Release 1.0

Scope:
- Marketplace listing: description, change notes, plugin icon (`pluginIcon.svg`, light and dark), screenshots of
  both viewers.
- `signPlugin` and `publishPlugin` configured with secrets in CI; the first upload done by hand for moderation.
- README: remove "planned" from the features that shipped, add screenshots and the settings page.
- Update the specification so it matches the shipped product: English-only UI (NFR-10 currently says Polish is
  optional), PolyForm Noncommercial license (the open questions still list MIT or Apache-2.0), and the answered open
  questions.

Done when:
- The plugin is approved on JetBrains Marketplace and installs from it into a clean IDE.
- CI publishes a signed build from a tag.

## Not in phase 2

WYSIWYG editing, three-way merge, other formats (AsciiDoc, reStructuredText), Mermaid and LaTeX rendering, a paid
tier. These stay as listed in the specification's out-of-scope section.
