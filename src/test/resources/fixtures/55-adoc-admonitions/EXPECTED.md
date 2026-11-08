# 55-adoc-admonitions

Admonition paragraphs (`NOTE: ...`) and delimited admonition blocks (`[WARNING]` + `====`).

- `# Warnings`: equal.
- `NOTE:` paragraph: title equal, text modified, inline added `unless you enable them in the settings` and removed `by default`. Rendered as an `ALERT` title plus a paragraph in an admonition container, like a GitHub alert.
- `[WARNING]` -> `[CAUTION]` block: the container type changes, so the new side carries the `Warning → Caution` note (`mdr-meta-changed`). The two inner paragraphs `Deleting a branch cannot be undone.` and `Check the target branch before you continue.` have equal text but a different container context; they pair as modified with an empty word diff and stay inside the frame. The container itself is not a row (ADR 0005).
- `TIP:` paragraph: equal (`Press F7 to jump to the next change.`), inside an admonition container.
- `[NOTE]` + `====` block: equal, one inner paragraph `The rendered diff uses the same colors as Markdown.` It exercises the delimited admonition form that is not a `NOTE:` paragraph.
