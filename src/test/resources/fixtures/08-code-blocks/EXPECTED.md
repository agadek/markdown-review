# 08-code-blocks

Code blocks (diff by line, no reflow, no Markdown word diff).

- `kotlin` block: modified. Line 2 changed (added argument `ignoreWhitespace = true`), line 3 `diff.blocks.forEach ...` added, other lines equal. Rendered as `<pre>` with line markers; the code content is not parsed as Markdown.
- Indented block: modified, `buildPlugin` -> `runIde`.
- Fenced without language -> with info string `json`: content equal, only the info string changes; block modified at metadata level.
- Paragraph with inline code: modified, inline `DiffTool` -> `FrameDiffTool` (inline code is a single token).
