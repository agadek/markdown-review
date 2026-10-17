# ADR 0001: How the rendered viewer becomes the default for Markdown diffs

Status: accepted (P2.2), checked against IntelliJ Platform 2025.1.3.

## Context

FR-11 asks for a setting that picks the viewer IntelliJ's diff window shows first for a `.md` file: rendered side by
side, rendered merged, or the built-in text viewer. Whatever the mechanism, the user must still be able to switch
viewers in the diff window, and the setting must keep control.

How `DiffRequestProcessor` (the diff window's controller) picks a viewer in 2025.1:

1. When the window is created it reads its tool order: `DiffSettingsHolder.DiffSettings.getSettings(place).diffToolsOrder`,
   a list of tool class names stored per *place* (`DiffPlaces`: `CommitDialog`, `ChangesView`, `VcsLogView`,
   `VcsFileHistoryView`, `Default` and others). Tools missing from the list are appended in the order of
   `DiffManagerEx.getDiffTools()`, which lists `diff.DiffTool` extensions **before** the built-in `SimpleDiffTool`,
   `UnifiedDiffTool`, `BinaryDiffTool` and `DirDiffTool`.
2. For each request it shows the first tool in that order whose `canShow()` is true.
3. When the user picks a viewer in the switcher, the window moves it to the top of the order and saves the order for
   its place, so the choice is remembered per kind of diff window.
4. `DiffUserDataKeysEx.FORCE_DIFF_TOOL` is read from the window's context, not from the request, and disables the
   switcher entirely.

Defaults: most places start with an empty order, so a plugin viewer already wins there; `VcsLogView` starts with
`SimpleDiffTool, UnifiedDiffTool` and `VcsFileHistoryView` with `UnifiedDiffTool`, so the text viewer wins there.

## Options considered

- **`DiffToolSubstitutor`**: replaces the first fitted tool with another one. It is `@ApiStatus.Internal`, and the
  substitute also replaces the text viewer when the user picks it in the switcher, so there is no way back.
- **`FORCE_DIFF_TOOL`**: fixes one tool for the whole window and hides the switcher. Phase 1 set it on the request in
  **Compare Markdown Files...**, where it had no effect.
- **Tool order in `DiffSettings`** (chosen): public, not marked internal, and it is the mechanism IntelliJ itself uses
  to remember the user's choice.

## Decision

`DefaultViewerOrder` rewrites the tool order of every known place (`DiffPlaces` constants used for file diffs) and of
every place already stored in `DiffSettingsHolder`'s state:

- rendered side by side or merged: that tool's class name moves to the front, the rest keeps its order;
- text: our tools move to the end, and `SimpleDiffTool` is listed first when no text tool is listed yet (otherwise the
  unlisted plugin tools would still come first).

Our tools accept only Markdown requests, so the order changes nothing for other files.

The order is written when the setting changes (Settings | Tools | Markdown Review, Apply) and once after the plugin
is installed or updated (a startup activity compares the setting with `appliedDefaultViewer` in the plugin's state).
Between those moments IntelliJ's own behavior applies: a viewer picked in a diff window stays first for that kind of
window. **Compare Markdown Files...** no longer forces a tool; it opens in the `Default` place, so it follows the
setting as well.

## Consequences

- With the setting on rendered, a Markdown diff in the commit window opens rendered; on text, it opens in IntelliJ's
  text viewer. Non-Markdown diffs are unaffected.
- A diff window that is already open keeps the order it read when it opened.
- Places that other plugins define and that have never been opened are not rewritten; there the default order applies
  (rendered first) until the user opens them once and changes the setting again.
- `DiffSettingsHolder` lives in an `impl` package. `verifyPlugin` reports no internal API use for it, and the
  integration tests (`DefaultViewerOrderTest`) compute the effective order with `DiffRequestProcessor`'s own public
  method, so a change in the platform shows up in CI.
