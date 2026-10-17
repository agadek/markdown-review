# 25-alerts

GitHub alerts (`> [!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]`, `[!CAUTION]`). The marker line is an alert title block keyed by the type; the content is flattened like a quote's, inside a `blockquote.mdr-alert.mdr-alert-<type>`.

- `# Alerts`: equal.
- NOTE: title equal, paragraph modified, inline `when it is available` added.
- `[!WARNING]` -> `[!CAUTION]`: the title is modified with the note "Warning → Caution" (`mdr-meta-changed`), word diff `Warning` -> `Caution`; the paragraph is equal (it only changes color).
- TIP: equal.
- A plain quote that becomes an `[!IMPORTANT]` alert: an added alert title; the paragraph stays equal.
