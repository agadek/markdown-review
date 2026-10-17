# 01-paragraph-edit

Paragraph edits (word diff inside a block).

- Heading `Installation`: equal.
- Paragraph 1: modified. Inline: added `directly`, changed `2025.1` -> `2025.2`.
- Paragraph 2: modified (heavily rewritten). Inline: removed `restart the IDE to enable the diff view`, added `no IDE restart is needed`. Block matching should still yield `modified`, not removed + added (shared prefix `After installation,`).
- Paragraph 3: equal.
