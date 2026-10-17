# 36-emoji

GitHub emoji shortcodes, converted to Unicode from the bundled table (`src/main/resources/pl/mdreview/emoji.txt`).

- `# Status :rocket:`: equal; renders as `Status 🚀`.
- `passes :white_check_mark:` -> `fails :x:`: modified; the word diff removes `passes ✅` and adds `fails ❌`. `:smile:` renders as 😄 on both sides.
- Unknown shortcode changed: modified; the shortcodes stay as text. `10:30:00` is not touched.
- Shortcode in inline code: equal and not converted.
