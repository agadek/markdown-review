# ADR 0007: The same viewers, settings and actions for both formats

Status: proposed, phase 4.

## Context

The IDE layer is built around one format: the two `diff.DiffTool`s are named "Rendered Markdown (side by side)" and
"(merged)", `MarkdownFiles` decides which files they accept, the action is **Compare Markdown Files...**, **Show
Rendered Diff** and the export actions filter for Markdown changes, and the settings page is Settings | Tools |
Markdown Review. [ADR 0001](0001-default-diff-viewer.md) makes the chosen viewer the default through the diff tool
order, keyed by tool class name.

## Options considered

- **A second pair of viewers for AsciiDoc**: separate default-viewer settings per format, but four rendered entries
  in every diff window's switcher, two of which never apply, and ADR 0001's order logic doubled.
- **The same viewers for both formats** (chosen): one entry per mode in the switcher, the default viewer setting and
  the remembered per-place order cover both formats.
- **Rename the plugin** to something format-neutral: the Marketplace listing, plugin ID and existing users' trust are
  tied to "Markdown Review". Changing the ID loses installs; changing only the display name is possible later and is
  not part of phase 4.

## Decision

- `RenderedSideBySideTool` and `RenderedMergedTool` stay (class names unchanged, so ADR 0001's stored orders stay
  valid). Their names become "Rendered (side by side)" and "Rendered (merged)", with tooltips naming both formats.
- `MarkdownFiles` becomes `ReviewFiles` with `formatOf(file): DocumentFormat?`. AsciiDoc is recognized by the
  extensions `adoc` and `asciidoc` and by the AsciiDoc plugin's file type, through a second
  `markdownFileTypeRecognizer`-style extension registered in `markdown-review-withAsciiDoc.xml`. `.asc` is accepted
  only through the file type, since it is also the extension of armored PGP files.
- `RenderedMarkdownDiffTool.canShow` accepts a request when both sides that exist have the same format, and that
  format is available (AsciiDoc only while an `AsciidoctorProvider` is registered, [ADR 0003](0003-asciidoctorj-from-asciidoc-plugin.md)).
  A pair of different formats, such as a rename from `.md` to `.adoc`, keeps the text viewer.
- The viewer passes `DocumentFormat` in `RenderRequest`. While Asciidoctor is starting (NFR-12), the viewer shows a
  "Preparing AsciiDoc renderer..." notice in the panel instead of a blank page, then renders.
- **Compare Markdown Files...** keeps its action ID and becomes **Compare Rendered Files...**; it accepts two files of
  the same supported format. **Show Rendered Diff** and **Export Rendered Diff** accept AsciiDoc changes, and their
  descriptions say "Markdown or AsciiDoc".
- Settings | Tools | Markdown Review keeps its name and settings; they apply to both formats. Render Mermaid
  diagrams and Render math apply to AsciiDoc once P4.7 ships. A line under the settings says whether AsciiDoc diffs
  are available and links to the AsciiDoc plugin when they are not.
- The large-file limits, ignore-whitespace, collapse-unchanged, remote images, pairing threshold, block actions,
  block editing, block menu and error reporter apply to AsciiDoc unchanged.

## Consequences

- One mental model for users: the same buttons and settings for both formats.
- Existing users see the viewer names change from "Rendered Markdown" to "Rendered" after the update; the change
  goes in the CHANGELOG.
- Block source editing of an AsciiDoc block edits AsciiDoc source; the editor textarea is format-agnostic already.
- The plugin's name still says Markdown; the listing and README say "Markdown and AsciiDoc" from the release on.
