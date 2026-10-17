# Review flow

How a change reaches the diff view:

```mermaid
flowchart LR
    A[Open file] -->|compare| B[Diff engine]
    B --> C[Renderer]
```

The diagram above is drawn in the rendered view.
