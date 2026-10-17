# 37-pairing-threshold

Four rewritten paragraphs between two unchanged headings. Each side has several paragraphs, so the full pairing
threshold applies (Settings | Tools | Markdown Review, "Pair changed blocks at word similarity of at least").
Word similarity of each old/new pair:

| Paragraph | Similarity |
|---|---|
| `The build runs every night ...` | 0.86 |
| `Release notes are ...` | 0.42 |
| `Tests must pass ...` | 0.60 |
| `Contributors sign ...` -> `Questions go to ...` | 0.13 |

At threshold 0.3:
- the build, release notes and tests paragraphs: modified, with a word diff;
- contributors paragraph: removed; questions paragraph: added.

At threshold 0.7:
- the build paragraph: modified;
- the other three old paragraphs: removed, then the three new ones: added.
