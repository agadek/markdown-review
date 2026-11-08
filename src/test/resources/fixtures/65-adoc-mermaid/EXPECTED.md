# 65-adoc-mermaid

Five `[mermaid]` listing blocks, each one `MERMAID` block (P4.7):

- Sequence diagram: equal, drawn once per side.
- Flowchart `Start --> Check{Valid?}`: modified (an edge now goes to a new `Fix` node). Both drawings side by side
  with a "Show source changes" toggle; the line diff shows `Check -->|no| Start` modified and `Fix --> Start` added.
- Pie chart: removed. Its paragraph "Removed diagram:" becomes "Added diagram:" (modified paragraph).
- State diagram: added.
- Flowchart `X --> Y`: modified; the new source has a syntax error (`X --> Y -->`). The page shows Mermaid's error
  message on the new side and opens the toggle, so the source line diff is what the reader sees.
- Class diagram: modified only by `format=svg` -> `format=png`. Mermaid itself does not read the attribute, but it is
  part of the block's key, so the row shows both drawings and the toggle; the source diff shows the style line as a
  modified fence row.

The block source range covers the `[mermaid]` style line and both `----` delimiters. With the "Render Mermaid
diagrams" setting off, each block is diffed as code: the `[mermaid]` fence line, then one code line per line of the
diagram, so the flowchart's changed edge and added line are marked without any `mdr-mermaid` element.
