# Plan: Markdown Review (IntelliJ plugin)

Goal: review changes to `.md` files in rendered form. Requirements:
1. Side-by-side comparison with differences highlighted (removed: red background, added: green).
2. Merged view: removed text red and struck through, added text green.
3. Side-by-side text mode.
4. Compare any two files (not only VCS).
5. Revert/apply changes per block (like the arrows in the text diff) and simple editing.

## Decisions
- Kotlin + IntelliJ Platform Gradle Plugin 2.x, IC 2025.1+, JDK 21.
- Parsing and rendering: commonmark-java (+ tables, strikethrough, task lists).
- Block diff and word diff inside a block: java-diff-utils, so the engine runs and is tested without the IntelliJ Platform.
- Diff window, text mode, navigation, VCS: IntelliJ's `DiffTool` infrastructure (no custom window).
- Rendered view: JCEF (`JBCefBrowser`), with a message and a fallback to text mode when JCEF is unavailable.
- Diff on the AST, not on finished HTML (better control over tables, code and row alignment).
- The IDE layer talks to the core only through `pl.mdreview.api.MarkdownDiffRenderer` (see `docs/ENGINE_CONTRACT.md`).

## Architecture
1. **Diff engine** (`pl.mdreview.engine`, pure logic, testable): AST -> blocks (heading, paragraph, list item, table row, code line, front matter line) -> LCS matching -> status `equal/modified/removed/added` -> word diff for `modified`. Model: `DiffBlock(status, left, right, inline)`, each `Block` with its source range.
2. **HTML generator** (`pl.mdreview.render`): side-by-side view (CSS grid, aligned rows) and merged view (`<ins>`/`<del>`), styled by `diff.css` with light/dark themes.
3. **IDE integration** (`pl.mdreview.ide`): two `DiffTool`s (rendered side-by-side, merged) registered in `plugin.xml`; `canShow()` only for two Markdown texts. Optionally a `DiffToolSubstitutor` as the default viewer for `.md` (to verify in the 2025.1 API) and a settings page.
4. **"Compare Markdown Files..." action**: pick any two files, `DiffManager.showDiff` with a `SimpleDiffRequest`. Native Ctrl+D, VCS comparison and history work without extra work.
5. **Text mode**: IntelliJ's built-in viewer (not implemented by us).

## Milestones
- M1: diff engine + unit tests (headings, lists, tables, code, moved blocks). Done.
- M2: HTML generator + CSS for views 1 and 2. Done.
- M3: JCEF panel, toolbar, change navigation, ignore whitespace. Done.
- M4: `DiffTool` registration, two-file action, integration tests (SimpleDiffRequest, Git). Done; the default viewer uses the diff tool order instead of a substitutor ([ADR 0001](docs/adr/0001-default-diff-viewer.md)).
- M5: polish (themes, large files, YAML front matter, images, raw HTML). Done.
- M6: revert/apply changes per block (commonmark source positions via `IncludeSourceSpans`, `WriteCommandAction`, `JBCefJSQuery`) + editing a block's source (double-click -> field with raw Markdown). Done: revert/apply in 1.0.0, source editing in 1.2.0. Full WYSIWYG (HTML -> MD) rejected: it normalizes the file and clutters the diff.

## Roadmap
- Phase 1: M1–M4 in their core form.
- Phase 2 ([docs/PHASE2.md](docs/PHASE2.md)): complete. 1.0.0 on JetBrains Marketplace, then 1.1.x; math, moved blocks and block source editing in 1.2.0.
- Phase 3 ([docs/PHASE3.md](docs/PHASE3.md)): in progress. The feedback loop (P3.1) and the block menu from P3.4 shipped in 1.2.0.

## Risks
- Block matching heuristics (rewritten paragraph vs removal + addition).
- Diffing tables and code blocks (best done by rows/lines).
- JCEF availability in some IDE distributions.
- Losing the cursor after a redraw while editing; undo must go through the IDE document.

## Publishing and monetization
- JetBrains Marketplace: `verifyPlugin`, `buildPlugin`, `signPlugin`, `publishPlugin`; first release by hand + moderation.
- Support: GitHub Sponsors (`.github/FUNDING.yml`), optionally Buy Me a Coffee; possibly a Pro version via the Marketplace (freemium).
- License: PolyForm Noncommercial 1.0.0 (copying allowed, not for sale or profit).
