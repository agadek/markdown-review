# 20-large-file

Large file (performance and stability; generated deterministically, seed 42).

- `old.md`: 119811 bytes, 2791 lines, 500 sections (headings, paragraphs, lists every 5, tables every 25, code every 50).
- Expected changes (17):
- `Section 1`: paragraph modified (added `(changed)` in the first sentence).
- `Section 38`: paragraph modified (added `(changed)` in the first sentence).
- `Section 75`: paragraph modified (added `(changed)` in the first sentence).
- `Section 112`: paragraph modified (added `(changed)` in the first sentence).
- `Section 149`: paragraph modified (added `(changed)` in the first sentence).
- `Section 186`: paragraph modified (added `(changed)` in the first sentence).
- `Section 223`: paragraph modified (added `(changed)` in the first sentence).
- `Section 260`: paragraph modified (added `(changed)` in the first sentence).
- `Section 297`: paragraph modified (added `(changed)` in the first sentence).
- `Section 334`: paragraph modified (added `(changed)` in the first sentence).
- `Section 371`: paragraph modified (added `(changed)` in the first sentence).
- `Section 408`: paragraph modified (added `(changed)` in the first sentence).
- `Section 445`: paragraph modified (added `(changed)` in the first sentence).
- `Section 482`: paragraph modified (added `(changed)` in the first sentence).
- `Section 100`: added paragraph `New paragraph added in the large file.` (added).
- `Section 300`: added paragraph `New paragraph added in the large file.` (added).
- `Section 450` and `Section 451`: whole sections removed.
- All other blocks equal.
- Requirement: computing the diff and generating HTML in reasonable time (PLAN.md M5 goal: no EDT freeze; measured in a performance test).
