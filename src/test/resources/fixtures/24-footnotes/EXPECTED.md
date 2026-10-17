# 24-footnotes

GFM footnotes: references (`[^label]`) and definitions (`[^label]: text`). Each definition is its own block, shown where it is in the source.

- `# Footnotes`: equal.
- `The engine compares blocks[^engine] and words[^words].`: equal. On the new side `[^words]` has no definition, so it renders as plain text, but the source is the same.
- `Rendering uses commonmark-java[^cm]` -> `... and JCEF[^jcef].`: modified, inline `and JCEF` and the reference `jcef` added. References render as superscript links to the definition on the same side (`#mdr-fn-<side>-<label>`).
- `[^engine]`: equal.
- `[^words]`: removed.
- `[^cm]: Version 0.24.` -> `0.30.`: modified (definitions pair by label), inline `24` -> `30`.
- `[^jcef]`: added.
