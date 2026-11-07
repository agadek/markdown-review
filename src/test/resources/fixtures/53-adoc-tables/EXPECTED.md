# 53-adoc-tables

Tables: diff by row, then by cell. The first row is a header row (`options="header"`).

- `# Support Matrix`, intro paragraph and trailing paragraph: equal.
- Header row `IDE | Version | Notes`: equal (no column is added or removed).
- `IntelliJ IDEA | 2025.1 | Supported since 2024.`: equal.
- `PyCharm`: modified. Cell `2025.1` -> `2025.2`; the `a|` cell changes its AsciiDoc from `*Community* and _Professional_.` to `*Community* and _Professional_ editions.` and gains the word `editions` in its rendered text. The cell content is compared as text inside the row, not block by block.
- `WebStorm`: modified. `2024.3` -> `2025.1`, `Tested with JCEF.` -> `Supported.`
- `Rider`: removed. `GoLand`: added (different first cell, so they must not pair as modified).
