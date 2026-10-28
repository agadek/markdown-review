# Phase 3 plan

Phase 2 ([PHASE2.md](PHASE2.md)) ends with the 1.0 release on JetBrains Marketplace. Phase 3 covers what comes after:
the features phase 2 deferred past 1.0, more IDEs, deeper VCS and code-review integration, performance, and the work
around the project itself (contributors, sponsorship, licensing).

Unlike phase 2, the order here should follow what 1.0 users ask for. The milestones below are the default order;
P3.1 is the place to re-rank them once the first feedback arrives.

Requirement IDs (FR-*, NFR-*) refer to the [specification](SPECIFICATION.md).

## Overview

| Milestone | Theme | Depends on | Release |
|-----------|-------|------------|---------|
| P3.1 | Feedback loop after 1.0: issue triage, crash reports, 1.0.x fixes | 1.0 | 1.0.x |
| P3.2 | Deferred from phase 2: block source editing, moved blocks | 1.0 | 1.1 |
| P3.3 | Other JetBrains IDEs and remote development | 1.0 | 1.1 |
| P3.4 | VCS and code review integration | P3.3 | 1.2 |
| P3.5 | Performance and large documents | 1.0 | 1.2 |
| P3.6 | Rendering options and sharing | P3.5 | 1.3 |
| P3.7 | Community, sponsorship and licensing | 1.0 | ongoing |

P3.2, P3.3, P3.5 and P3.7 are independent and can run in parallel. P3.4 waits for P3.3, because review windows look
different in each IDE and remote setup. P3.6 waits for P3.5, because both change how the page is built and updated.

## P3.1 Feedback loop after 1.0

Scope:
- GitHub issue templates (bug report with IDE version, OS, JCEF status and a minimal `old.md` / `new.md` pair;
  feature request), and labels for triage.
- An `ErrorReportSubmitter` so exceptions from the plugin open a prefilled GitHub issue from the IDE's error dialog,
  with no data sent anywhere else.
- Every confirmed rendering or diff bug gets a fixture pair before it is fixed, so the fixture set grows with real
  documents.
- Re-rank P3.2–P3.6 from the issues and Marketplace reviews.

Status: issue templates (bug, crash, feature), the `crash`, `triage` and `needs fixture` labels and the error
reporter (`GitHubErrorReporter`) are in place for 1.2.0; the fixture rule is in `src/test/resources/fixtures/README.md`.

Done when:
- Issue templates and the error reporter are live, and a test exception from `runIde` opens a prefilled issue.
- At least one 1.0.x release is out with fixes from user reports, each covered by a fixture.

## P3.2 Deferred from phase 2

What phase 2 kept out of 1.0. If a part of it ships in 1.0 after all, drop it here.

Scope:
- **Block source editing** (FR-13, P2.6): shipped in 1.2.0.
- **Moved-block detection** (FR-15, P2.7): shipped in 1.2.0.

Done when:
- The done criteria of P2.6 and P2.7 in PHASE2.md are met.

## P3.3 Other JetBrains IDEs and remote development

The plugin only depends on `com.intellij.modules.platform`, so it should already load in every IDE built on the
IntelliJ Platform. This milestone makes that a tested promise instead of an assumption.

Scope:
- Add PyCharm, WebStorm, GoLand, PhpStorm, RubyMine, CLion, Rider, DataGrip and RustRover to `pluginVerification`,
  and Android Studio with its own version mapping.
- Manual check in each IDE family that the diff window offers the rendered viewers, JCEF works, and the Compare
  action is in the menus. Rider and Android Studio get extra attention, as their diff and VCS windows are the most
  customized.
- **Remote development** (Gateway / JetBrains Client, and the split-mode IDE): check where the viewer runs and whether
  JCEF is available on the client. If the rendered view cannot run on the client, the viewers must report
  `canShow = false` there so the text diff stays, as without JCEF today (NFR-6).
- Update the Marketplace listing and README with the supported IDEs.

Done when:
- `verifyPlugin` reports "Compatible" for every IDE in the list, in CI.
- The manual checklist is filled in for each IDE family, with screenshots for the listing.
- In a remote development session the plugin either works or falls back to the text diff without errors.

## P3.4 VCS and code review integration

Scope:
- **Pull and merge request review**: in the GitHub and GitLab review windows, let the reviewer start a review
  comment from a rendered block, mapped to the block's source line through `BlockInfo`. First verify which API the
  bundled GitHub and GitLab plugins expose for this; if there is no public extension point, fall back to "Copy line
  link" and opening the text diff at that line.
- **Rendered preview in the commit window**: show the rendered diff of the selected `.md` file in the commit
  window's diff preview, not only in the separate diff window.
- **History and blame**: "Show Rendered Diff" in the Git log and file history for a selected revision of a `.md` file,
  and a per-block "Show when this changed" that opens the history at the block's lines.
- **Merge conflicts**: a rendered view in the three-way merge window for `.md` files, read-only first (left, result,
  right rendered, conflicts highlighted), resolution still in the text merge. The specification lists three-way merge
  as out of scope for 1.0; this is the first step past it.

Done when:
- A review comment started from a rendered block lands on the right line of a GitHub pull request (and GitLab merge
  request, if the API allows).
- The commit window's preview shows the rendered diff for `.md` files.
- A `.md` merge conflict can be viewed rendered, and the text merge remains the default for resolving it.

Status:
- The bundled GitHub and GitLab plugins add review comments only to the text viewer and have no public extension
  point for other viewers, so the fallback applies: a block's right-click menu has **Open in Text Diff Here**, which
  reopens the window in the text viewer at the block's line, where the comment gutter is.
- The commit window's diff preview and file history already use the diff window's viewers, so the rendered viewers
  are there with the viewer switcher; nothing to add.
- **Show History for This Block** in the same menu runs the platform's "Show History for Selection" on the block's
  lines.
- The rendered merge view is not started.

## P3.5 Performance and large documents

Scope:
- **Incremental updates**: today every document change reloads the whole page with `loadHTML`. Patch only the
  changed rows in the DOM through `JBCefJSQuery`, so editing a large file does not flicker or lose scroll position.
- **Incremental diff**: re-diff only the sections around the edit (headings already act as anchors) instead of the
  whole document.
- **Lazy rendering**: render rows near the viewport first and the rest on scroll, so very large files open quickly.
- **Raise the large-file threshold** from P2.3 once the above holds, and keep the message for files beyond it.
- Track the benchmark from P2.3 across releases and fail CI on a regression of more than 20%.

Done when:
- Typing in a 10,000-line file with the rendered diff open keeps the editor responsive and the view stable.
- A 20,000-line file opens in the rendered view in under 2 s on a typical laptop.
- The CI benchmark guards against regressions.

## P3.6 Rendering options and sharing

Scope:
- **Custom stylesheet**: an optional user CSS file applied on top of the theme variables, for teams that want the
  rendered view to match their documentation site.
- **Export the diff**: save the rendered diff as a standalone HTML file (styles inlined, no scripts) to attach to a
  review or a ticket. PDF through the system print dialog if JCEF allows it.
- **More syntax** driven by P3.1 feedback, for example wiki links (`[[page]]`), GitLab-specific syntax or MDX-style
  components, each with a fixture pair.

Done when:
- A custom CSS file changes the rendered view and survives theme switches.
- An exported HTML diff opens in a browser offline and looks like the in-IDE view.

## P3.7 Community, sponsorship and licensing

Scope:
- `CONTRIBUTING.md` (build, run, fixtures, the English-only rule), a pull request template, a `CHANGELOG.md` that
  feeds the Marketplace change notes, and "good first issue" labels.
- Sponsorship: GitHub Sponsors is already in `FUNDING.yml`; link it from the README, the Marketplace listing and the
  plugin's settings page.
- **Commercial use** (decided): the PolyForm Noncommercial license stays, and companies get a commercial license
  through GitHub Sponsors. Add a sponsor tier for commercial use, state its terms in the README and `LICENSE` notes,
  and link it from the Marketplace listing. The plugin itself has no license check.
- A short documentation page (GitHub Pages) with screenshots, supported elements and settings.

Done when:
- A first outside contribution is merged following `CONTRIBUTING.md`.
- The README and Marketplace listing say how to sponsor and that a commercial license comes with the GitHub Sponsors
  commercial tier.
- The commercial tier is live on GitHub Sponsors.

## Not in phase 3

WYSIWYG editing, other document formats (AsciiDoc, reStructuredText), editors outside the JetBrains platform (VS
Code, Fleet), and telemetry. Each can be reopened from P3.1 feedback.
