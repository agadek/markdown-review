# Review flow

How a change reaches the diff view:

```mermaid
flowchart LR
    A[Open file] -->|diff with HEAD| B[Diff engine]
    B --> C[Renderer]
    C --> D[JCEF preview]
```

The diagram above is a plain code block today.
