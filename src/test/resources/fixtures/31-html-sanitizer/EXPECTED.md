# 31-html-sanitizer

Dangerous raw HTML is added; the render must stay inert (NFR-8).

- `# Sanitizer` and `Safe paragraph.`: equal.
- Every other block: added.
- No `<script>`, `<iframe>`, `style`, `on*` attribute or `javascript:` URL survives, including the entity-encoded `jav&#x61;script:` and the `<img/onerror=...>` form. The content of `<script>` is dropped.
- Safe markup stays: the `<div>`, the link texts, `<img src="x.png">`, `<span>`, and `<a href="https://example.com/ok">`.
