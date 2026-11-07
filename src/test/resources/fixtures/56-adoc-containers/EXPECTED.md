# 56-adoc-containers

Example, sidebar, quote and open blocks, including a sidebar nested in an example. Containers are not rows: they frame the blocks inside them (ADR 0005).

- `# Containers`, `Intro paragraph before the containers.` and `Closing paragraph after the containers.`: equal.
- Example block title `Example block`: equal; inner paragraph `Example content.` -> `Example content, revised.`: modified, inline added `, revised`.
- Sidebar block title `Sidebar`: equal; inner paragraph `Sidebar content.` -> `Sidebar content with extra detail.`: modified, inline added `with extra detail`.
- Quote block: inner paragraph modified, removed `whatever`. The quote attribution `[quote, Ada Lovelace]` is unchanged; if it changes it is part of the quote block's metadata.
- Open block: inner paragraph modified, added ` with an inline bold phrase`; the new side shows `bold` in bold.
- Nested example: outer paragraph `Outer content.` equal; inner sidebar paragraph `Inner sidebar content.` -> `Inner sidebar content, revised.`: modified, inline added `, revised`. The inner block keeps both example and sidebar frames (nested containers).
- `'''` thematic break: equal `THEMATIC_BREAK`.
- `<<<` page break: equal; the block kind is part of the key, so a thematic break and a page break never pair.
- `++++` passthrough block: equal `HTML` block. It is sanitized and compared by visible text (`Raw HTML from a passthrough block.`); `<strong>` is formatting, not a word.

All changed paragraphs are inside their containers, so the engine must descend rather than mark a whole container modified.
