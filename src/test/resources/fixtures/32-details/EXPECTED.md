# 32-details

`<details>` / `<summary>` spanning several Markdown blocks, and one written as a single HTML block.

- `# FAQ`: equal.
- First `<details>`: its summary is a `DETAILS` block (equal); the first paragraph inside is equal; `next to` -> `instead of` in the second paragraph: modified. The group renders as `<details class="mdr-details" open>` because it contains a change.
- Second `<details>`: all equal; renders closed (`<details class="mdr-details">` without `open`).
- One-block `<details>`: an `HTML` block, modified (`changed` added); it renders with `open` because it changed.
- The closing `</details>` lines are not blocks.
