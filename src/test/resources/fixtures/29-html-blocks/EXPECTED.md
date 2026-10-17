# 29-html-blocks

HTML blocks compared by their visible text, rendered sanitized.

- `# Badges`: equal.
- `<p align="center">` block: modified. The word diff runs on the visible text, not the tags: `IntelliJ` removed, `JetBrains IDEs` added; the image and `<br>` stay.
- `<div align="center">` -> `<div align="left">`: modified with an empty word diff; the block gets the `HTML changed` note (`mdr-meta-changed`).
- `Text after the HTML.`: equal.
