# 06-nested-lists

Nested, ordered and task lists. The diff unit is the list item.

Bullet list:
- `Diff engine`, `AST parsing`, `LCS matching`, `blocks`: equal.
- `words` -> `words and characters`: modified (level 3), inline added `and characters`.
- `moves` (level 3): added.
- `HTML generator` and `IDE integration` swapped: one equal, the other removed + added (or moved).
- Nesting must be preserved in the render (indentation), including for removed blocks.

Ordered list:
- `Second step` -> `Second, revised step`: modified.
- `4. Fourth step`: added.

Task list:
- `[ ] Unit tests` -> `[x] ...`: modified (checkbox state only; text equal).
- `Plan`: equal.
