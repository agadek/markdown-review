# 41-wiki-links

Wiki links as in Obsidian, Foam and MediaWiki: `[[target]]`, `[[target|label]]`, `[[target#section]]`. They render as
links whose text is the label (or the target when there is none).

- `# Wiki links`: equal.
- `[[Getting Started]]`: equal, rendered as a link to `Getting%20Started` with the text "Getting Started".
- `[[Install Guide|...]]`: modified, inline `install` -> `setup` inside the link text; the target stays `Install%20Guide`.
- `[[Configuration#Proxy]]` -> `[[Configuration#Network proxy|Configuration#Proxy]]`: modified, same visible text with a
  new target, so the link is marked as a changed link (`mdr-link-changed`), as for ordinary links in 09-links-images.
- The last paragraph: equal. Inline code, an escaped `\[[`, an Obsidian embed `![[...]]` and GitLab's table of contents marker `[[TOC]]` stay
  as written, not links.
