# 05-headings

Headings: text, level and syntax changes.

- `# Markdown Review`: equal.
- `## Features` -> `## Capabilities`: modified (text change).
- `### Side-by-side view` -> `#### ...`: modified (level change only; same text, different size when rendered). Empty word diff, change marked at block level.
- `### Merged view` -> `### Unified view`: modified, inline `Merged` -> `Unified`.
- Setext `Setext section\n---` -> ATX `## Setext section`: equal in the AST (same level 2 and text). The difference is only in the source, visible in text mode.
- Paragraphs `View description.` (2x) and `Section content.`: equal.
