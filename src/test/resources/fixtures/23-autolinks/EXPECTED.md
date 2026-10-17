# 23-autolinks

GFM autolinks: bare `https://` and `www.` URLs are links, as on GitHub.

- `# Links`: equal.
- Bare docs URL: modified, inline `welcome` -> `getting-started` (word diff inside the link text). Both sides render an `<a href>`.
- `www.example.com`: equal, rendered as a link to `http://www.example.com`.
- `<https://example.com/contact>` (CommonMark autolink): equal.
