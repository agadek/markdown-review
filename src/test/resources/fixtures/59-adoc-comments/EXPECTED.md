# 59-adoc-comments

Line (`//`) and block (`////`) comments. They are `HTML_COMMENT` blocks, hidden when rendered unless they changed (FR-24).

- `# Notes`: equal.
- Line comment `// TODO: add screenshots` -> `// TODO: add screenshots of the merged view`: modified `HTML_COMMENT`, word diff adds `of the merged view`.
- `Text between comments.`: equal.
- Added line comment `// Added: explain the generated section.`: added `HTML_COMMENT`. It has no counterpart on the old side.
- Block comment: modified `HTML_COMMENT`. Line 2 `Do not edit by hand.` -> `Review before editing.`; the opening `////`, closing `////` and `Generated section starts here.` are equal.
- Unchanged line comment `// This comment stays unchanged.`: equal and hidden (it has no visible row unless changed comments are shown).
- `Paragraph with an inline note.` and `Visibly unchanged paragraph.`: equal.

The comment rows carry their comment text in the render, like Markdown `mdr-comment` rows.
