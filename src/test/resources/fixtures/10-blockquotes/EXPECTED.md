# 10-blockquotes

Block quotes (container block with blocks inside).

- Quote 1: modified. Same paragraph, added line `— John Gruber` (soft break, same paragraph) -> inline added.
- Quote 2: inner paragraph 1 modified (`experimental` -> `stable`), inner paragraph 2 `It may be removed.` removed. The engine is expected to descend into the blocks inside the quote rather than marking the whole quote as modified.
- Nested quote: `Level 1` equal, `Level 2` modified (added `, changed`), `Level 3` added (new nesting level).
