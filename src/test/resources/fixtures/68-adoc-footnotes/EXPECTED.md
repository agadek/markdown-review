# 68-adoc-footnotes

AsciiDoc footnotes (`footnote:[text]` and `footnote:id[text]`) are FOOTNOTE blocks after the document's other
blocks, with the same rendering and diff behavior as Markdown footnote definitions.

- Paragraph 1: modified. `added note` is added before the existing footnotes; `stable note` is unchanged; the
  `changed` footnote text changes from `old text` to `new text`.
- Paragraph 2: removed with its `removed note` footnote.
- Paragraph 3: equal. The `shared` id is referenced twice; both references point at one definition.
- Paragraph 4: equal despite Asciidoctor renumbering the later footnotes after the insertion.
- Footnote blocks: `added note` added, `stable note` equal, `changed` modified (same explicit id), `removed note`
  removed, `shared` equal, `last note` equal. The unchanged footnotes stay equal when a footnote is inserted before
  them because their labels are the explicit id or the footnote text, not Asciidoctor's numeric index.
- Every `href="#mdr-fn-<side>-..."` in the rendered page matches an existing `id` on the same side, in both views.
- A footnote definition has `edit = null`; reverting the paragraph that contains its `footnote:[...]` macro reverts
  the footnote as part of that one edit. Its source line is the paragraph of the first reference, so Ctrl/Cmd+click
  lands on the footnote macro rather than at the end of the file.
