# 12-whitespace-only

Changes only in whitespace and syntax with no effect on content.

Expected result with whitespace ignored (default): no changes, all blocks equal.

Without ignoring whitespace (M3 option), the only difference in the render is:
- `Line with trailing spaces.` + 3 trailing spaces: in the new file this is a hard line break (`<br>`), in the old one a soft break. This is the only change visible in the render, so the paragraph is modified, with the added break as the only word change (P2.4: a hard break is a change).

The other changes are invisible in the render and must yield equal in both modes:
- extra spaces after `#` in the heading,
- paragraph re-wrapped (soft wrap),
- list marker `-` -> `*` and wider indentation,
- fewer blank lines before the last paragraph.

Text mode (built-in viewer) shows all of these differences.
