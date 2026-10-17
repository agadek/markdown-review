# 09-links-images

Links and images.

- Paragraph 1: modified. Link text change (`documentation` -> `platform documentation`) and a URL-only change on the second link (text `repository` equal). The URL change must be detected even though the rendered text is identical: mark the link as changed (e.g. marker + tooltip with the old URL).
- Image 1: modified, only `src` changes (alt and title equal). Side-by-side view shows both images (the files may not exist; the render must not break on a missing image).
- Image `Diagram`: added.
- Paragraph with the reference link: text equal, but the `[mp]` definition changes the target URL. Reference definitions are not blocks in the commonmark AST; the change should mark the paragraph using `[mp]` as modified (or be ignored if the engine compares only the render; pin it down in the test).
- Autolink: equal.
