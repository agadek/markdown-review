# 57-adoc-block-titles

Block titles (`.Title`) are `BLOCK_TITLE` rows before the block they title, so a changed title is its own change (ADR 0005).

- `# Titles`: equal.
- `.Original title` -> `.New title`: modified `BLOCK_TITLE`, inline `Original` -> `New`. Its example block `Content in a titled example.` and the example delimiter frame: equal.
- No title -> `.Modern example`: added `BLOCK_TITLE` before the second example. The example paragraph `Content without a title.`: equal.
- `.Deprecated notice` -> no title: removed `BLOCK_TITLE` before the literal block. The literal block `literal content`: equal.
- `.Retained title` before the `[source,java]` block: equal `BLOCK_TITLE`. The source block is modified (`1` -> `2`), and the title must not be folded into the code line diff.
