# 27-mermaid

A ` ```mermaid ` block whose diagram changes: one edge label renamed and one node added.

With Mermaid rendering on (the default), the whole fence is one `MERMAID` block, compared as a unit:

- Heading, intro paragraph and closing paragraph: equal.
- The diagram: one modified block. The renderer draws the old and the new diagram side by side (stacked in the
  merged view) and adds a "Show source changes" toggle with the line diff below.
- The toggle's line diff follows `08-code-blocks`: the fence is equal, `A[Open file] -->|compare| B[Diff engine]` is
  modified (word diff `compare` -> `diff with HEAD`), `B --> C[Renderer]` is equal and `C --> D[JCEF preview]` is
  added.

With the "Render Mermaid diagrams" setting off, the fence is diffed as a code block, exactly as before P2.8: the
fence and `B --> C[Renderer]` are equal blocks, the edge line is a modified block and the new node an added block.
