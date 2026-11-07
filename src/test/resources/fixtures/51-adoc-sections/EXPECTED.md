# 51-adoc-sections

Section titles, section levels, discrete headings and the document title.

- Document title `Product Guide`: equal (`HEADING`, level 1).
- `== Overview`: equal.
- `=== Installation` -> `==== Installation`: modified (level change only; same text). Empty word diff; the new side carries the `mdr-meta-changed` note `h3 → h4` (`data-change-note`).
- `== Configuration` -> `== Settings`: modified, inline `Configuration` -> `Settings`.
- `[discrete] == Release Notes` -> `[discrete] === Release Notes`: modified (discrete heading level change only). Empty word diff; note `h2 → h3`.
- Paragraphs `A short overview of the product.`, `Install the product from the marketplace.`, `Configuration is stored in the project settings.` and `What changed in this release.`: equal.
