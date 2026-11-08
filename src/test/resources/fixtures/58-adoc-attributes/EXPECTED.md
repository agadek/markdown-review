# 58-adoc-attributes

Header and body attribute entries. Attribute references are substituted by Asciidoctor, so a changed entry changes both the entry row and every block whose text it changes (ADR 0005).

- Document title `Release Notes`: equal.
- Header `:product-name: Markdown Review`: equal (`ATTRIBUTE`).
- Header `:product-version: 1.3` -> `1.4`: modified `ATTRIBUTE`, inline `1.3` -> `1.4`.
- Header `:min-ide: 2024.3`: equal.
- Paragraph 1 source is identical on both sides. It is modified because `{product-version}` renders differently: word diff `1.3` -> `1.4`; `Markdown Review` and `2024.3` stay equal. The block key includes the plain text, so the source-equal block still pairs as modified.
- Body `:status: beta` -> `stable`: modified `ATTRIBUTE`, inline `beta` -> `stable`.
- Paragraph 2 source is identical on both sides. It is modified because `{status}` renders differently: word diff `beta` -> `stable`.
- `All other details are unchanged.`: equal.
