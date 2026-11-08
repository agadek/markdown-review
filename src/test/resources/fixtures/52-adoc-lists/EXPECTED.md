# 52-adoc-lists

Nested, ordered, checklist and description lists, plus a list continuation. The diff unit is the list item.

Section headings:
- `# Release Tasks`, `## Checklist`, `### Bullet list`, `### Ordered list`, `### Checklist items`, `### Description list` and `## Continuation`: equal.

Bullet list:
- `Diff engine`, `AST parsing`, `LCS matching`, `blocks`: equal.
- `words` -> `words and characters`: modified (level 3), inline added `and characters`.
- `moves` (level 3): added.
- `HTML generator` and `IDE integration` swapped: one equal, the other removed + added (or moved).
- Nesting must be preserved in the render (indentation), including for removed blocks.

Ordered list:
- `Second step` -> `Second, revised step`: modified.
- `4. Fourth step`: added.

Checklist:
- `[ ] Update the changelog` -> `[x] ...`: modified (checkbox only). Empty word diff; the new side carries the `unchecked → checked` note (`mdr-meta-changed`).
- `[x] Bump the version`: equal.
- `[ ] Tag the release` -> `[ ] Tag the release on GitHub`: modified, inline `on GitHub` added, no checkbox note.

Description list (`[horizontal]`):
- Terms `` `old.adoc` `` and `` `new.adoc` `` and their descriptions: equal.
- Term `` `EXPECTED.md` ``: equal; its description `The expected changes.` -> `The expected block changes.`: modified, inline `changes` -> `block changes`.

Continuation:
- Item `Source blocks`: equal.
- Attached `[source,adoc]` listing: equal; the list item is compared without its attached block.
- Attached paragraph `The block above is a section heading.` -> `... section title.`: modified, inline `heading` -> `title`.
- `Closing paragraph.`: equal.
