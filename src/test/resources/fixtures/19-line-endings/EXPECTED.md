# 19-line-endings

Line endings change CRLF -> LF, identical content.

- `old.md` uses CRLF, `new.md` LF. Do not normalize these files (e.g. via git autocrlf): see `.gitattributes` in the fixtures directory.
- Expected: all blocks equal in the rendered view, regardless of the ignore-whitespace option.
- Source positions (sourceRange) in `old.md` must account for `\r\n` so per-block revert does not corrupt the file.
