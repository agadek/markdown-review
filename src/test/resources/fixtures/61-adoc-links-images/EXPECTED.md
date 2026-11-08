# 61-adoc-links-images

Links, xrefs and images: link text changes are word diffs, target changes are empty word diffs with a link/image target note (FR-18).

- `# Links and Images`: equal.
- Link `documentation` -> `user documentation` (same URL): modified paragraph, inline added `user ` before the link. No target note.
- Link `https://example.com/download[download page]` -> `https://example.com/releases[download page]`: modified paragraph, empty word diff, `link target changed` note on the new side.
- `xref:installation.adoc[installation guide]` -> `xref:setup.adoc[installation guide]`: modified paragraph, empty word diff, xref target note. The link text is equal.
- `<<settings>>` -> `<<configuration>>`: modified paragraph, empty word diff, cross-reference target note. The anchor line `[[settings]]` -> `[[configuration]]` is a prefix line of the section, not a block row; the section heading `Settings` and its text are equal.
- Inline image `image:images/icon.png` -> `images/icon-dark.png`: paragraph modified, empty word diff, image target note. Alt text `Icon` and size are equal.
- Block image `image::images/screenshot.png` -> `images/screenshot-dark.png`: `IMAGE` block modified, empty word diff, image target note. Alt text is equal.
- `A plain paragraph without links.`: equal.
