# Phase 4 plan: AsciiDoc

Phases 1 to 3 built a rendered diff for Markdown. Phase 4 brings the same review experience to AsciiDoc files:
`.adoc` changes open rendered in IntelliJ's diff window, block by block, with word diffs, navigation, per-block
revert and apply, block editing, the block menu and export, exactly as Markdown does today.

The 1.0 specification listed AsciiDoc as out of scope, and [PHASE3.md](PHASE3.md) kept it out of phase 3. This plan
reopened it. The requirements below get the next free IDs (FR-16 onward, NFR-11 onward) and move into
[SPECIFICATION.md](SPECIFICATION.md) when they ship, like the phase 2 and phase 3 ones did.

Architecture decisions are recorded as ADRs in [docs/adr/](adr/), and every milestone has a spec in
[docs/specs/phase4/](specs/phase4/) with its scope, design, tests and done criteria.

## Goals

- A reviewer who opens a changed `.adoc` file sees it rendered, aligned block by block, with the same colors,
  controls and settings as a Markdown diff.
- Markdown users are not affected: no larger download, no slower Markdown rendering, no behavior change.
- One diff engine, one HTML generator, one page and one export for both formats. AsciiDoc adds a parser front end,
  not a second plugin inside the plugin.

## Non-goals

- Rendering AsciiDoc the way a site generator would: no resolved includes, no Asciidoctor extensions
  (asciidoctor-diagram, PlantUML, Kroki), no Antora cross-references between components, no Asciidoctor PDF themes.
- A second math engine. Formulas are drawn by the bundled KaTeX only; AsciiMath is converted to LaTeX for it
  (P4.9, [ADR 0008](adr/0008-asciimath-to-latex.md)) rather than drawn by MathJax.
- Comparing a Markdown file with an AsciiDoc file (for example a rename from `README.md` to `README.adoc`); such a
  pair keeps the text diff.
- reStructuredText and other formats. ADR 0004 keeps the door open, nothing more.

## Requirements

### Functional

| ID | Requirement |
|---|---|
| FR-16 | AsciiDoc files (`.adoc`, `.asciidoc`, and any file of the AsciiDoc plugin's file type) open in the rendered viewers wherever Markdown files do: the Commit window, Git log, file history, branch comparison, pull request review, **Show Rendered Diff** and **Compare Rendered Files...**. |
| FR-17 | The block diff of FR-1 to FR-4, FR-6 and FR-7 for AsciiDoc: section titles, paragraphs, list items (unordered, ordered, checklists, description lists, callout lists), table rows, lines of listing and literal blocks, admonitions, block titles, images, thematic and page breaks, and the document header are aligned in rows, with word diffs in modified blocks. |
| FR-18 | Changes the visible text does not show are marked, as for Markdown: a changed section level, admonition type, block title, source language, link or image target, cross-reference target, checklist state, or an attribute entry. |
| FR-19 | Delimited blocks keep their structure: example, sidebar, quote, verse, open and admonition blocks frame the blocks inside them like a Markdown blockquote or alert does, on both sides of the diff. |
| FR-20 | Per-block revert and apply (FR-12), block source editing (FR-13), Ctrl/Cmd+click to source (FR-14), moved blocks (FR-15) and the block menu (P3.4) work on AsciiDoc blocks, through the IDE document, with one Undo step per edit. |
| FR-21 | HTML and PDF export, multi-file export and the custom stylesheet (P3.6) work for AsciiDoc diffs; a multi-file export may mix Markdown and AsciiDoc files. |
| FR-22 | `[mermaid]` listing blocks are drawn with the bundled Mermaid and `stem:[...]` / `latexmath:[...]` / `[stem]` blocks with the bundled KaTeX, with the same toggles and source diff as Markdown diagrams and formulas. |
| FR-23 | Document attributes defined in the file's own header or body are applied, so `{product-version}` renders as its value and changing an attribute entry shows as a change on the entry and on every block whose text it changes. `include::` directives are not followed; each is shown as its own block with the target, and a changed target is a change. |
| FR-24 | AsciiDoc comments (`//` and `////`) are hidden unless they changed, like HTML comments in Markdown. |
| FR-25 | AsciiMath formulas (`asciimath:[...]`, `[asciimath]` blocks, and `stem` when the document's `stem` attribute is unset or `asciimath`) are drawn with the bundled KaTeX, compared and toggled like `latexmath` formulas; a formula that cannot be converted stays source and is marked. |

### Non-functional

| ID | Requirement |
|---|---|
| NFR-11 | AsciiDoc support is active only while the JetBrains AsciiDoc plugin (`org.asciidoctor.intellij.asciidoc`) is installed and enabled ([ADR 0003](adr/0003-asciidoctorj-from-asciidoc-plugin.md)). Without it, `.adoc` diffs keep the text viewer and nothing fails or logs errors; the settings page says what to install. |
| NFR-12 | Performance: after the first AsciiDoc render in an IDE session, the NFR-2 budget applies to AsciiDoc too (1,000 lines under 300 ms, 10,000 lines under 2 s). The first render, which starts Asciidoctor, runs off the EDT with a "Preparing AsciiDoc renderer..." notice and takes under 5 s on a typical laptop. |
| NFR-13 | Security: Asciidoctor runs in `SECURE` safe mode, reads no files and no network, and its output goes through the same HTML allowlist as Markdown (NFR-8). The page's CSP does not change. |
| NFR-14 | Markdown is unchanged: every existing fixture and test passes without edits to its expectations, and the Markdown benchmark stays within 5% of 1.3.0. The plugin zip grows by no more than 1 MB. |
| NFR-15 | The core stays free of IntelliJ Platform APIs (ENGINE_CONTRACT): the AsciiDoc front end depends on AsciidoctorJ only, and is unit-tested with AsciidoctorJ on the test classpath. |

Measured for NFR-12 in P4.1 (Apple Silicon, Oracle JDK 21.0.9, 2 GB heap): the first
`Asciidoctor.Factory.create()` took 1.57–2.14 s and the first 1,015-line load 236–266 ms, so the first
render is about 1.8–2.4 s. Steady state, median of 10: 1,015 lines 60–65 ms and 10,003 lines
138–295 ms for load plus all inline conversion. The budget holds; NFR-12 stays as written.

## Architecture decisions

| ADR | Decision |
|---|---|
| [0002](adr/0002-asciidoc-parser.md) | Parse AsciiDoc with AsciidoctorJ (`load` with `sourcemap`), not with Asciidoctor.js in JCEF, the AsciiDoc plugin's PSI or a parser of our own. |
| [0003](adr/0003-asciidoctorj-from-asciidoc-plugin.md) | Take AsciidoctorJ from the JetBrains AsciiDoc plugin through an optional dependency instead of bundling it. |
| [0004](adr/0004-format-neutral-engine.md) | One format-neutral engine and HTML generator: a `DocumentFormat` in `RenderRequest`, a front end per format producing `Block`s, and block content that is either commonmark nodes or sanitized HTML. |
| [0005](adr/0005-asciidoc-block-model.md) | How AsciiDoc maps onto blocks: which nodes become blocks, keys and plain text from Asciidoctor's converted inline HTML, line ranges from the source map plus a line scanner, word marks applied to HTML text runs. |
| [0006](adr/0006-asciidoc-rendering-reuse.md) | Reuse the page, CSS variables, `mdr-*` classes, Mermaid, KaTeX and export; our own block markup around Asciidoctor's inline HTML, never Asciidoctor's stylesheet or block HTML. |
| [0007](adr/0007-asciidoc-ide-integration.md) | The same two viewers, settings page and actions serve both formats, with format-neutral names; a pair of different formats keeps the text diff. |
| [0008](adr/0008-asciimath-to-latex.md) | Draw AsciiMath by converting it to LaTeX in the engine, in Kotlin, for the bundled KaTeX; no JavaScript converter and no MathJax. |

## Overview

| Milestone | Theme | Depends on | Spec | Status |
|-----------|-------|------------|------|--------|
| P4.1 | Spike: AsciidoctorJ from the AsciiDoc plugin | – | [P4.1](specs/phase4/P4.1-asciidoctorj-spike.md) | Done |
| P4.2 | Format-neutral engine and API, Markdown unchanged | – | [P4.2](specs/phase4/P4.2-format-neutral-engine.md) | Done |
| P4.3 | AsciiDoc block extraction | P4.1, P4.2 | [P4.3](specs/phase4/P4.3-asciidoc-block-extraction.md) | Done |
| P4.4 | AsciiDoc HTML rendering and change marks | P4.3 | [P4.4](specs/phase4/P4.4-asciidoc-rendering.md) | Done; light/dark check at release |
| P4.5 | Edit regions, revert, apply, block editing and moves | P4.3 | [P4.5](specs/phase4/P4.5-asciidoc-edit-regions.md) | Done |
| P4.6 | IDE integration | P4.1, P4.4 | [P4.6](specs/phase4/P4.6-asciidoc-ide-integration.md) | Done; manual runIde check at release |
| P4.7 | Diagrams and math in AsciiDoc | P4.4 | [P4.7](specs/phase4/P4.7-asciidoc-diagrams-math.md) | Done |
| P4.8 | Fixtures, documentation and release | P4.4–P4.7, P4.9 | [P4.8](specs/phase4/P4.8-asciidoc-docs-release.md) | Done: release PR prepared; Marketplace publication pending |
| P4.9 | AsciiMath in AsciiDoc | P4.7 | [P4.9](specs/phase4/P4.9-asciidoc-asciimath.md) | Done; visual/export check at release |

P4.1 and P4.2 run in parallel: the spike needs no engine change, and the refactoring needs no AsciiDoc. P4.1
confirmed [ADR 0003](adr/0003-asciidoctorj-from-asciidoc-plugin.md); if it had failed, the fallback in that ADR
(bundling AsciidoctorJ in a companion plugin) would replace it before P4.3 starts. P4.4 and P4.5 are independent once
blocks exist.
P4.6 can start on P4.4's output with edit actions disabled, and turns them on when P4.5 lands.

Release: AsciiDoc ships behind no flag in 1.4.0, in one minor release once P4.1 to P4.9 are done. Mermaid blocks and
formulas show as source when the corresponding setting is off.

## P4.1 Spike: AsciidoctorJ from the AsciiDoc plugin

Scope:
- An optional `<depends>` on `org.asciidoctor.intellij.asciidoc` with its own descriptor, and a throwaway action
  that loads AsciidoctorJ through it, parses a sample file with `sourcemap` and prints the block tree with lines.
- Measure the first `Asciidoctor.Factory.create()` and the steady-state `load` time on the fixture sizes of NFR-2.
- Check that two threads can `load` on one instance at once, or settle on serializing calls.
- Check how line numbers survive preprocessor directives (`ifdef`, `include::` in `SECURE` mode), list items,
  table cells and description list entries.
- Run `verifyPlugin` with the dependency on every IDE in the verification list.

Done when:
- The spike's findings are written into [ADR 0003](adr/0003-asciidoctorj-from-asciidoc-plugin.md) and
  [ADR 0005](adr/0005-asciidoc-block-model.md), and both are accepted or revised.

P4.1 results: go for the optional dependency. Gradle resolves
`plugin("org.asciidoctor.intellij.asciidoc", "0.44.10")` for IC 2025.1.3; 0.44.10 ships AsciidoctorJ 3.0.0 and
JRuby 9.4.8.0, while the 2026.1 and 263 verifications resolve 0.45.11 with AsciidoctorJ 3.0.1 and JRuby 9.4.14.0.
Both versions produce the same block tree and lines. `verifyPlugin` is Compatible on IC 2025.1.3 and IU 2026.1;
IU 263 has only the eight VCS problems the baseline already has. The source map is present on every node, with the
conditional-tail, block-title, comment, include, admonition and table-row gaps listed in
[ADR 0005](adr/0005-asciidoc-block-model.md). `shutdown()`/`close()` stops the Ruby threads but does not release
the class loader on JDK 21; the IDE unload was not run, and [ADR 0003](adr/0003-asciidoctorj-from-asciidoc-plugin.md)
records that as a known limitation for P4.6.

## P4.2 Format-neutral engine and API

Scope:
- `DocumentFormat` (`MARKDOWN`, `ASCIIDOC`) in `RenderRequest`, default `MARKDOWN`.
- `Block` no longer holds a commonmark `Node` directly but a `BlockContent` (commonmark nodes, or sanitized HTML with
  its text runs); the Markdown front end fills the first, the HTML generator renders both.
- `BlockExtractor` becomes the Markdown front end behind a `BlockSource` interface; the diff engine takes blocks.
- Link-change detection and word marks work on HTML text runs as well as on commonmark nodes.

Done when:
- All existing tests and fixtures pass unchanged, and the benchmark is within 5% of 1.3.0.

P4.2 results: the engine is format-neutral and the full test suite passes with unchanged Markdown output. The
benchmark reports 8 ms / 6 ms for 1,000 lines and 90 ms / 79 ms for 10,000 lines (side by side / merged), well within
the NFR-2 budget.

## P4.3 AsciiDoc block extraction

Scope:
- `AsciiDocBlockSource`: Asciidoctor's document tree to `Block`s per [ADR 0005](adr/0005-asciidoc-block-model.md),
  with kinds, keys, plain text, contexts and line ranges.
- Document header, attribute entries, comments and `include::` lines from a line scanner.
- AsciiDoc fixtures (`old.adoc` / `new.adoc` / `EXPECTED.md`) run by the fixture test.

Done when:
- The AsciiDoc fixtures listed in the spec give the expected block statuses and word diffs.

P4.3 results: `AsciiDocBlockSource` and `AsciiDocLines` cover the tree and scanner rules in
[ADR 0005](adr/0005-asciidoc-block-model.md); fixtures 50-64 pass with the expected block kinds, statuses, line
ranges, containers and word diffs. The integration test renders every fixture through the shared HTML generator in
both modes.

## P4.4 AsciiDoc HTML rendering and change marks

Scope:
- Block markup for every AsciiDoc kind, with Asciidoctor's converted inline HTML inside, sanitized.
- Word marks, link and image target changes, and change notes (section level, admonition type, block title,
  language, checklist state).
- Styles for the AsciiDoc-only elements in `diff.css` and `CLASSES.md`, from the existing CSS variables.

Done when:
- Every AsciiDoc fixture renders in both modes, light and dark, with no unstyled Asciidoctor markup and no change
  that is visible only in the source.

P4.4 results: the renderer handles the AsciiDoc kinds, nested containers, admonition blocks, table cells, image and
include targets, word marks and the new change notes. Renderer snapshots for `56-adoc-containers` and
`61-adoc-links-images` pass in both modes, and fixture `31-html-sanitizer` is unchanged. The light/dark and `runIde`
visual review remains for the release pass (P4.8).

## P4.5 Edit regions, revert, apply, block editing and moves

Scope:
- `EditRegions` for delimited blocks, tables, lists with continuations and the header.
- Moved blocks across AsciiDoc kinds.

Done when:
- Reverting or applying every changed block of every AsciiDoc fixture turns one side into the other, and each is one
  Undo step.

P4.5 results: `AsciiDocRegionRules` covers prefix lines, delimited code, whole containers and tables, list
continuations, the header, comments and includes, and moves. Every fixture applies and reverts exactly and re-parses
to the target block tree; revert and apply are one Undo step. Two changes derived from another edit (an admonition
container type and an attribute value) have `edit = null` because no single line edit covers them; reverting the
source edit resolves them, and the round trip stays exact.

## P4.6 IDE integration

Scope:
- File recognition, the viewers' `canShow`, **Compare Rendered Files...**, **Show Rendered Diff**, export, settings and the
  missing-plugin state, per [ADR 0007](adr/0007-asciidoc-ide-integration.md).
- The first-render notice and Asciidoctor's lifetime (created once, released when the AsciiDoc plugin unloads).

Done when:
- In `runIde` with the AsciiDoc plugin, a changed `.adoc` file opens rendered from the Commit window, the Git log and
  **Compare Rendered Files...**; without the plugin it opens in the text viewer with no errors.

P4.6 results: `ReviewFiles` classifies both formats, the viewers accept only a pair of the same supported format,
the actions say "Markdown or AsciiDoc", and the optional descriptor installs one `PluginAsciidoctorProvider` and
registers the AsciiDoc file type. AsciidoctorJ is not bundled (the plugin zip stays at 2.2 MB) and is created lazily.
A headless test renders a real AsciiDoc diff through the provider. `verifyPlugin` is Compatible on IC 2025.1.3 and
IU 2026.1; IU 263 reports only the baseline VCS compatibility problems, none from AsciiDoc or AsciidoctorJ. The
`runIde`/manual check remains for P4.8, as does the failure notice with **Open Text Viewer**.

## P4.7 Diagrams and math in AsciiDoc

Scope:
- `[mermaid]` blocks as `MERMAID` blocks, `stem` / `latexmath` inline and block formulas as `MATH`.

Done when:
- An AsciiDoc fixture with changed diagrams and formulas renders like fixtures 28 and 40.

P4.7 results: `[mermaid]` listing and literal blocks are one `MERMAID` block with the same drawing, changed-diagram
and source-diff behavior as Markdown; `latexmath` / `stem` inline and block formulas are `MATH` atoms and blocks,
drawn with the shared KaTeX. Fixtures `65-adoc-mermaid` and `66-adoc-math` cover unchanged, changed, added, removed,
unparsable and attribute-only cases. AsciiMath and other asciidoctor-diagram styles stay source.

## P4.8 Fixtures, documentation and release

Scope:
- Requirements into SPECIFICATION.md, AsciiDoc into ENGINE_CONTRACT.md, README, plugin description, docs site,
  CHANGELOG and screenshots.
- Issue templates accept `.adoc` pairs.

Done when:
- The release is on Marketplace, and the docs describe what is and is not supported.

P4.8 results: FR-16 to FR-25 and NFR-11 to NFR-15 are in SPECIFICATION.md as shipped for 1.4.0; ENGINE_CONTRACT,
README, plugin description, site, CHANGELOG, CONTRIBUTING and the issue templates describe the released scope;
fixtures 50-68 and their README are current; the AsciiDoc screenshots are in `docs/screenshots/`. A release PR from
`release-1.4.0` to `main` is prepared with `pluginVersion=1.4.0`. Marketplace publication, the tag and the manual
`runIde` checks remain with the maintainer.

## P4.9 AsciiMath in AsciiDoc

Added to phase 4 after P4.1 to P4.8 were merged. AsciiMath is Asciidoctor's default math notation, so leaving it
undrawn left most AsciiDoc formulas as source.

Scope:
- `AsciiMathToTex`, a Kotlin converter from AsciiMath to LaTeX in the AsciiDoc front end, per
  [ADR 0008](adr/0008-asciimath-to-latex.md).
- AsciiMath inline and block formulas as `MATH` atoms and blocks drawn by KaTeX, keyed and diffed by their AsciiMath
  source; a formula the converter rejects stays source and is marked.

Done when:
- Fixtures `66-adoc-math` and `67-adoc-asciimath` render with AsciiMath drawn, in both modes, light and dark, and
  their exports include the drawings; Markdown fixtures are unchanged.

P4.9 results: `AsciiMathToTex` converts the reference subset to LaTeX for KaTeX; inline and block AsciiMath keep their
source in `data-source`, keys and the word diff. Fixture `66-adoc-math` now draws AsciiMath and checks a spelling-only
change, and fixture `67-adoc-asciimath` covers unset `:stem:`, inline and block forms, matrices, a vector and rejected
input. The docs drop "AsciiMath is not supported".

Release: P4.9 ships in 1.4.0, and the release waits for it. P4.8's manual `runIde` checks run once, on the build
with P4.9.

## Not in phase 4

reStructuredText, resolved includes, Asciidoctor extensions, mixed-format pairs and an editor outside
the JetBrains platform. Each can be reopened from feedback on the AsciiDoc release.
