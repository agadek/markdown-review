# 62-adoc-conditionals

Preprocessor conditionals around paragraphs. Conditions are evaluated with the document's own attributes; the directive lines are hidden rows so a changed condition is visible (ADR 0005).

- Document title `Conditionals` and header attributes `:product-version: 1.4`, `:product-release: 1.4`: equal.
- `ifdef::product-version[]` -> `ifdef::product-release[]`: modified directive row (`HTML_COMMENT`-like). Empty word diff; the row shows the changed condition.
- Paragraph inside the `ifdef`: `This documentation covers 1.4.` equal on both sides. Both attributes are defined, so both conditions are true and the paragraph stays visible; only the condition text changed.
- `ifndef::internal-build[]` and `endif::[]`: equal.
- Paragraph inside the `ifndef`: modified, inline added ` on every platform`.
- `The rest of the page is always shown.`: equal.

Content skipped by a false condition is not shown in the rendered diff; that behavior is not exercised by this pair.
