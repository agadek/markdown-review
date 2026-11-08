# ADR 0009: A setting per format for the rendered diff

Status: accepted, after 1.4.0.

## Context

From 1.4.0 the rendered viewers take Markdown and AsciiDoc diffs ([ADR 0007](0007-asciidoc-ide-integration.md)).
Some users want only one of them rendered: a team that reviews AsciiDoc in an Antora build may prefer the text diff
for `.adoc`, and someone who installed the plugin for AsciiDoc may want Markdown left alone. The default viewer
setting ([ADR 0001](0001-default-diff-viewer.md)) cannot express this: it is one tool order per kind of diff window,
shared by both formats, and the only way to keep the AsciiDoc text diff today is to disable the AsciiDoc plugin.

## Options considered

- **A default viewer per format**: the diff tool order is per diff place, not per file type, so a second order cannot
  be stored next to the first. It would need `canShow` to lie for one format depending on the chosen viewer, which is
  what this option reduces to anyway, with a more confusing setting.
- **Stop installing AsciiDoc support** when AsciiDoc is turned off: saves nothing, because Asciidoctor starts lazily on
  the first AsciiDoc diff (NFR-12), and turning it back on would then depend on the startup activity or a restart.
- **Two checkboxes that gate the format in `ReviewFiles.isSupported`** (chosen).

## Decision

- `MarkdownReviewSettings` gets `renderMarkdown` and `renderAsciiDoc`, both `true` by default, so existing users see
  no change. `isEnabled(format)` answers for one format.
- `ReviewFiles.isSupported(format)` is true only when the format is enabled and available
  (`ReviewFiles.isAvailable(format)`: Markdown always, AsciiDoc while `AsciiDocSupport` is installed). Every rendered
  entry point already asks it: `RenderedMarkdownDiffTool.canShow`, **Show Rendered Diff**, **Export Rendered Diff**
  and **Compare Rendered Files...**. A turned-off format therefore gets IntelliJ's text viewer, and the actions hide
  for it, exactly as AsciiDoc does while the AsciiDoc plugin is missing.
- The AsciiDoc integration (startup activity, dynamic plugin listener, provider) is installed as before; the setting
  only decides whether it is used. Turning AsciiDoc back on needs no restart.
- The settings page shows the two checkboxes on one row under the default viewer. The availability line for the
  AsciiDoc plugin stays as it is.
- Diff windows already open keep their viewer; the setting applies to diffs opened after Apply.

## Consequences

- One place answers "does the plugin render this format", so a new entry point gets the switch for free.
- With both formats off, the plugin renders nothing; that is allowed, and the plugin can simply be disabled instead.
- The default viewer setting still applies to the formats that are on.
