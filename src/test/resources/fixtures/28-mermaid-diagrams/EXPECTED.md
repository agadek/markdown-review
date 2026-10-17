# 28-mermaid-diagrams

Four ` ```mermaid ` diagrams, each its own `MERMAID` block (P2.8):

- Sequence diagram: equal, drawn once per side.
- Flowchart `Start --> Check{Valid?}`: modified (an edge now goes to a new `Fix` node). Both drawings side by side
  with a "Show source changes" toggle; the line diff shows `Check -->|no| Start` modified and `Fix --> Start` added.
- Pie chart: removed. Its paragraph "Removed diagram:" becomes "Added diagram:" (modified paragraph).
- State diagram: added.
- Flowchart `X --> Y`: modified; the new source has a syntax error (`X --> Y -->`). The page shows Mermaid's error
  message on the new side and opens the toggle, so the source line diff is what the reader sees.
