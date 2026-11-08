# Fixtures: Markdown and AsciiDoc file pairs

Each `<case>/` directory contains:
- `old.md` / `new.md` for a Markdown pair, or `old.adoc` / `new.adoc` for an AsciiDoc pair (the versions before and after the change, left and right side),
- `EXPECTED.md`: expected changes at block level (`equal` / `modified` / `added` / `removed`, optionally `moved`) and in the word diff.

Cases 01-41 are Markdown. Cases 50-66 are AsciiDoc.

Every confirmed rendering or diff bug gets a case here before it is fixed, built from the reporter's files cut down to
the smallest pair that still shows the problem.

The files are input for the diff engine tests (M1), the HTML generator (M2) and the preview. `.gitattributes` disables line-ending normalization because some cases depend on exact bytes.

| Case | What it checks |
|---|---|
| 01-paragraph-edit | paragraph edits, word diff |
| 02-block-added | blocks added in the middle of the file |
| 03-block-removed | removed section |
| 04-block-moved | moved section with unchanged content |
| 05-headings | text and level changes, Setext vs ATX |
| 06-nested-lists | nested, ordered and task lists |
| 07-tables | added column, changed/added/removed rows, alignment |
| 08-code-blocks | fenced, indented, info string, inline code |
| 09-links-images | link text vs URL, images, reference links |
| 10-blockquotes | quotes, nesting, blocks inside quotes |
| 11-front-matter | changed YAML front matter |
| 11b-front-matter-added | added front matter |
| 12-whitespace-only | whitespace and syntax only, no effect on render |
| 13-empty-both | both files empty |
| 14-empty-to-content | empty -> content |
| 15-content-to-empty | content -> empty |
| 16-whitespace-only-file | whitespace only -> empty |
| 17-identical | no changes |
| 18-mixed | realistic README, many change types at once |
| 19-line-endings | CRLF -> LF |
| 20-large-file | ~500 sections, performance |
| 21-task-lists | checkbox-only change, task text change, item becoming a task |
| 22-strikethrough | strikethrough added and removed on unchanged words |
| 23-autolinks | bare `https://` and `www.` URLs (GFM autolinks) |
| 24-footnotes | added, removed and changed footnote definitions, references |
| 25-alerts | GitHub alerts: content change, type change, quote becoming an alert |
| 26-relative-images | relative image paths resolved against each side's directory |
| 27-mermaid | Mermaid diagram with a renamed edge label and an added node: one modified diagram, line diff behind a toggle |
| 28-mermaid-diagrams | Mermaid diagrams: unchanged, changed, removed, added, and one whose new source does not parse |
| 29-html-blocks | changed HTML blocks: visible text and attribute-only changes |
| 30-inline-html | inline `<kbd>`, `<sub>`, `<sup>` |
| 31-html-sanitizer | dangerous raw HTML: `<script>`, `on*` attributes, `javascript:` URLs (NFR-8) |
| 32-details | `<details>` spanning several blocks, and in one HTML block |
| 33-html-comments | added and edited HTML comments, block and inline |
| 34-hard-line-breaks | hard line breaks with trailing spaces and with a backslash |
| 35-thematic-breaks | added, removed and syntax-only thematic breaks |
| 36-emoji | known and unknown emoji shortcodes |
| 37-pairing-threshold | rewritten paragraphs of different similarity, expectations at pairing thresholds 0.3 and 0.7 |
| 38-pairing-threshold-lone | one rewritten paragraph alone on each side, which pairs at half the threshold |
| 39-short-list-items | a short list item replaced by another that shares only common words, which must not pair |
| 40-math | inline and block formulas: equal, changed, added, unparsable, and dollar signs that are not formulas |
| 41-wiki-links | `[[target]]` and `[[target\|label]]` wiki links: changed label, changed target, and text that is not a link |
| 50-adoc-paragraph-edit | AsciiDoc paragraphs: word diff and a formatting-only change |
| 51-adoc-sections | section text and level changes, discrete headings, document title |
| 52-adoc-lists | nested, ordered, checklist and description lists, list continuations |
| 53-adoc-tables | changed, added and removed rows, header row, `a\|` cell |
| 54-adoc-listing | source blocks: language change, line changes, callouts |
| 55-adoc-admonitions | admonition paragraph and block, type change |
| 56-adoc-containers | example, sidebar, quote and open blocks, nested content changes |
| 57-adoc-block-titles | added, removed and changed `.Title` |
| 58-adoc-attributes | header and body attribute entries; a changed value changes a referencing paragraph |
| 59-adoc-comments | line and block comments added and edited; unchanged comments hidden |
| 60-adoc-includes | `include::` added, removed and retargeted; never resolved |
| 61-adoc-links-images | link text vs target, `xref:`, `<<id>>`, inline and block images |
| 62-adoc-conditionals | `ifdef` around a paragraph, condition changed |
| 63-adoc-moved | a section moved without changes |
| 64-adoc-large | ~500 sections, performance |
| 65-adoc-mermaid | `[mermaid]` blocks: unchanged, changed, removed, added, unparsable, attribute-only change |
| 66-adoc-math | inline and block `latexmath` / `stem` formulas, AsciiMath not drawn, changed, added, removed, unparsable |
