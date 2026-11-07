# 60-adoc-includes

`include::target[]` directives. Includes are never resolved (SECURE mode): each directive is an `INCLUDE` block whose key is the directive line, and a changed target is a change (FR-23, ADR 0005).

- `# Book`: equal.
- `include::chapters/intro.adoc[]` -> `include::chapters/introduction.adoc[]`: modified `INCLUDE`, changed target. The word diff marks `intro` -> `introduction`; the new row carries the include-target change note (the same kind of note as a changed link target).
- `include::chapters/installation.adoc[]`: removed `INCLUDE`.
- `include::chapters/setup.adoc[]`: equal `INCLUDE`.
- `include::chapters/appendix.adoc[]`: added `INCLUDE`.
- `The rest of the book.`: equal.

No chapter files exist and none may be read. The P4.3 front end must use the line scanner's `INCLUDE` row for each directive and drop Asciidoctor's SECURE-mode include paragraph for the same line, so each directive appears once.
