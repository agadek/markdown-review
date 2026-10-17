# 09-links-images

Links and images.

- Paragraph 1: modified. Link text change (`documentation` -> `platform documentation`, shown by the word diff) and a URL-only change on the second link (text `repository` equal). The URL-only change is marked on the link: class `mdr-link-changed`, `title` with the other side's URL (`Previous URL: ...` on the right and in the merged view, `New URL: ...` on the left).
- Image 1: modified, only `src` changes (alt and title equal). The image gets `mdr-img-changed` and the other side's `src` appended to its `title`. Side-by-side view shows both images (the files may not exist; the render must not break on a missing image).
- Image `Diagram`: added.
- Paragraph with the reference link: text equal, but the `[mp]` definition changes the target URL. Reference definitions are not blocks in the commonmark AST, so block keys include the resolved link targets: the paragraph using `[mp]` is modified (empty word diff) and its link is marked like the URL-only change above.
- Autolink: equal.
