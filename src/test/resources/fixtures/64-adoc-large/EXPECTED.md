# 64-adoc-large

Large AsciiDoc file (performance and stability; generated deterministically, no random source).

- `old.adoc`: 64731 bytes, 2754 lines, 500 sections. `new.adoc`: 64475 bytes, 2740 lines, 499 sections. Word choice is a deterministic function of the section number.
- Every section has a heading and one paragraph. Every 5th section also has a bullet list, every 25th a table with a header row, every 50th a `[source,kotlin]` block.
- Expected changes (11):
- `Section 1`, `Section 64`, `Section 128`, `Section 192`, `Section 256`, `Section 320`, `Section 384` and `Section 448`: paragraph modified, added `(changed)` at the end. Empty rest of the word diff.
- `Section 200` and `Section 400`: added paragraph `A new paragraph was added in the large AsciiDoc file.`
- `Section 275`: whole section removed (heading, paragraph, list and table). The new side has 499 sections.
- All other blocks equal.
- Requirement: computing the diff and generating HTML in reasonable time; measured by the AsciiDoc entries of `RenderBenchmarkTest` (NFR-12), not by this fixture alone.
