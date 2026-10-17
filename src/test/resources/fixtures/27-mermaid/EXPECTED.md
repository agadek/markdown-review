# 27-mermaid

A ` ```mermaid ` block whose diagram changes: one edge label renamed and one node added. Mermaid rendering is not
implemented yet, so this case pins today's behaviour: the block is diffed as a fenced code block, line by line
(same rules as `08-code-blocks`).

- Heading, intro paragraph and closing paragraph: equal.
- Fence (info string `mermaid`): equal.
- Line `A[Open file] -->|compare| B[Diff engine]`: modified, word diff `compare` -> `diff with HEAD`.
- Line `B --> C[Renderer]`: equal.
- Line `C --> D[JCEF preview]`: added.

What P2.8 (Mermaid diagrams and math rendering) will change: the whole `mermaid` fence becomes one block, compared
as a unit, so this case reports a single modified diagram instead of one modified and one added line. The renderer
then draws the old and new diagram side by side, with a "Show source changes" toggle that shows the line diff above.
