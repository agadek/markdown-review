# Markdown Review

An IntelliJ IDEA plugin for reviewing changes to Markdown files in **rendered** form instead of as raw text.

## Why

A text diff of Markdown shows syntax instead of content: a rewritten paragraph becomes a few red and green lines,
a change in a table becomes shifted `|` characters, and re-indenting a list looks like everything changed.
Markdown Review shows the same comparison the way the document looks once rendered, so the reviewer judges the
content, not the markup. The plugin does not open a window of its own: it adds new viewers to IntelliJ's regular
diff window, so it works wherever the IDE shows a diff of an `.md` file.

## What the plugin does

- **Rendered side-by-side diff.** The left and right versions are rendered in two columns, with blocks (headings,
  paragraphs, list items, table rows, code blocks) aligned in rows. Removed blocks have a red background, added ones
  a green background, and the other side keeps an empty placeholder of the same height. Inside changed blocks,
  individual words are highlighted.
- **Merged view.** One column: removed parts red and struck through, added parts green. Handy for reading a large
  change quickly.
- **Text diff.** IntelliJ's built-in diff is always one click away in the diff window's viewer switcher.
- **Per-block revert** (planned, M6). Arrows next to a block move it to the other side, like in the text diff, and a
  double click lets you edit the block's raw Markdown. Changes go through the IDE document, so Undo works.

The viewers work in the Commit window (Show Diff, Ctrl+D), in file history, when comparing with a branch and in pull
request windows. Compare any two files with **Compare Markdown Files...** from the project view context menu or the
Tools menu. F7 and Shift+F7 move between changes; Ctrl/Cmd+click on a block opens its source in the editor.

Status: early stage, see [PLAN.md](PLAN.md). The contract between the diff engine and the IDE layer is described in
[docs/ENGINE_CONTRACT.md](docs/ENGINE_CONTRACT.md).

## Requirements

IntelliJ IDEA (Community or Ultimate) 2025.1 or newer with JCEF support (the default in JetBrains Runtime). Without
JCEF the plugin stays out of the way: the diff window keeps the text viewer.

## Building

```
./gradlew buildPlugin
./gradlew runIde
```

## Development

Developed with the help of Claude Code, with every change reviewed by the maintainer.

## License

[PolyForm Noncommercial License 1.0.0](LICENSE). Noncommercial use (personal, educational, research, nonprofit
organizations) is allowed free of charge. Commercial use requires a separate license from the author.
