# 22-strikethrough

GFM strikethrough (`~~text~~`) added and removed on words that stay the same.

- `# Pricing`: equal.
- `10 EUR` struck through and `12 EUR` added: modified. The word diff marks only `12 EUR` (`ins.mdr-ins`); the struck-through `10 EUR` is a plain `<del>` (content strikethrough, no background), distinct from `del.mdr-del`.
- `~~trial lasts 14 days~~` -> `trial lasts 14 days`: modified (source changed), empty word diff; the formatting change shows in the render.
- `Old price: ~~20 EUR~~ 15 EUR.`: equal.
