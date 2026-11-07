# 50-adoc-paragraph-edit

Paragraphs in an AsciiDoc document (word diff inside a block, inline formatting changes).

- Document title `Installation Guide`: equal (`HEADING`).
- Paragraph 1: modified. Inline: added `directly`, changed `2025.1` -> `2025.2`.
- Paragraph 2: modified (heavily rewritten). Inline: removed `restart the IDE to enable the diff view`, added `no IDE restart is needed`. Block matching should still yield `modified`, not removed + added (shared prefix `After installation,`).
- Paragraph 3: modified (formatting only). The text is the same, the source loses `*` around `block menu`, so the block key changes while the word diff is empty (like Markdown markup-only changes).
- Paragraph 4: equal.

The AsciiDoc front end is not implemented yet; these expectations describe the result required by the P4.3 spec and ADR 0005.
