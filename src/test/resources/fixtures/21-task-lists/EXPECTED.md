# 21-task-lists

GFM task lists: checkbox state changes.

- `# Release checklist`: equal.
- `[ ] Update the changelog` -> `[x] ...`: modified, checkbox only. The word diff is empty; the new side carries the note "unchecked → checked" (`mdr-meta-changed`) and an outlined checkbox.
- `[x] Bump the version`: equal.
- `[ ] Tag the release` -> `[ ] Tag the release on GitHub`: modified, inline `on GitHub` added, no checkbox note.
- `Plain item` -> `[ ] Plain item`: modified (became a task); no note, since only one side has a checkbox.
