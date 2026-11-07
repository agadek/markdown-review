# 54-adoc-listing

Source and literal blocks: diff by line, no reflow, no word diff; the fence is compared by style and language.

- `[source,java]` block: modified. Line 2 changes (added argument `ignoreWhitespace = true`), a new line `diff.blocks.forEach { println(it.status) }` is added after it, and the existing `println(diff.blocks.size)` and `}` lines stay equal below. The callout markers are not in this block.
- `[source]` -> `[source,kotlin]` block: content equal (`val unchanged = true`), only the language changes, so the block is modified at metadata level with an empty line diff and the `language` change note.
- `[source,kotlin]` callout block: lines equal. It stays one `CODE_FENCE` plus `CODE_LINE`s; the `<1>` marker is part of the line text.
- Callout list item `<1>`: modified, inline added `and level`.
- Literal `....` block `$ ./gradlew test`: equal.
- `Literal block:` paragraph: equal.
