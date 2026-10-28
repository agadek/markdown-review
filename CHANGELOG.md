# Changelog

All notable changes to Markdown Review. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the version numbers follow [Semantic Versioning](https://semver.org/). The released sections below become the
change notes on JetBrains Marketplace, so write each entry for the plugin's users.

## [Unreleased]

### Added

- **Show Rendered Diff** in the context menu of the Git log's changed files, of file history and of the Commit window
  opens a Markdown change rendered, whichever viewer diffs open with by default.
- **Export** the rendered diff as a standalone HTML file (no scripts, images inlined) or a PDF, from the viewer's
  options menu.
- **Custom stylesheet:** a CSS file of your own on top of the rendered view, set in Settings | Tools | Markdown Review.
- **Wiki links:** `[[page]]`, `[[page|label]]` and `[[page#section]]` are rendered as links, and a changed target is
  marked like any changed link.
- Settings | Tools | Markdown Review links to the commercial license terms, next to the sponsorship links.

## [1.2.1]

### Fixed

- Compatibility with IntelliJ IDEA 2026.3: block history and crash reports no longer use API that 2026.3 deprecates or
  marks as internal.

## [1.2.0] - 2026-10-28

### Added

- **Math:** `$...$` and `$$...$$` formulas are drawn offline with KaTeX; a changed formula is highlighted as a whole,
  and a changed block formula can show its source diff. Turn it off with the new *Render math* setting.
- **Moved blocks** are labeled at both ends with a link to the other end, and reverting either end moves the block back
  in one Undo step.
- **Block source editing:** double-click a block to edit its raw Markdown in place; saving is one Undo step.
- **Block menu:** right-click a block to go to its source, open the text diff at its line or show the history of just
  that block.
- Plugin errors reported from the IDE open a prefilled crash report on GitHub.

## [1.1.1] - 2026-10-23

### Fixed

- Links in the rendered diff whose scheme is written in upper or mixed case, such as `HTTPS://`, now open in the
  browser.

## [1.1.0] - 2026-10-20

### Added

- Colors of added, removed and modified blocks and of changed words can be set per color scheme in Settings | Editor |
  Color Scheme | Markdown Review.

### Changed

- Expanded plugin description: why a rendered diff helps, navigation between changes and the supported IDE versions.

### Fixed

- The rendered viewers have their own icons in the viewer switcher, which IntelliJ 2026.2 turned from a drop-down into
  a row of icon buttons; before, they showed the text viewer's icon.

## [1.0.0] - 2026-10-19

First release on JetBrains Marketplace.

### Added

- Rendered side-by-side and merged diff viewers for Markdown files in IntelliJ's own diff window: Commit window, file
  history, branch comparison, pull requests and *Compare Markdown Files...*.
- Settings page (Settings | Tools | Markdown Review) with the default viewer for Markdown diffs, whitespace and
  collapse options, large-file limits, remote images, Mermaid and the pairing threshold.
- Pairing threshold: how similar a removed and an added block must be to show as one modified block with a word diff.
- Per-block revert and apply buttons on changed blocks of an editable side, each one Undo step.
- Mermaid diagrams drawn offline in the editor's theme, with a "Show source changes" toggle on a changed diagram.
- GitHub Flavored Markdown: tables, task lists, strikethrough, autolinks, footnotes and alerts.
- HTML blocks and inline HTML (sanitized), `<details>` sections, HTML comments, hard line breaks, thematic breaks and
  emoji shortcodes.
- A changed link target, heading level or alert type is marked as a change.
- Relative images resolve against the file; remote images load only when enabled.
- Very large files show a notice with an Open Text Viewer button instead of rendering.
- Plugin icon for light and dark themes.
