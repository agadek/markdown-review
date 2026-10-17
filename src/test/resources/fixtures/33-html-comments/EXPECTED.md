# 33-html-comments

HTML comments are invisible when rendered, so their changes are shown as muted comment rows.

- `# Notes` and `Text between comments.`: equal.
- `<!-- TODO: add screenshots -->`: modified (`HTML_COMMENT`); the word diff adds `of the merged view`. Shown as a `mdr-comment` row with the comment text.
- `<!-- Generated section starts here -->`: added `HTML_COMMENT`.
- Inline comment `inline note` -> `inline remark`: the paragraph is modified with an empty word diff and gets the `comment changed` note.
- An unchanged comment row is rendered with `hidden`.
