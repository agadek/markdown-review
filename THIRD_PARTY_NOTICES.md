# Third-party notices

The plugin distribution includes the following third-party software. Its own license
([PolyForm Noncommercial 1.0.0](LICENSE)) does not apply to these components.

## Mermaid 11.17.2

- Files: `src/main/resources/pl/mdreview/mermaid/mermaid.min.js` (the `dist/mermaid.min.js` file of the npm package
  `mermaid@11.17.2`, unmodified), license text in `src/main/resources/pl/mdreview/mermaid/LICENSE`.
- Source: https://github.com/mermaid-js/mermaid
- Integrity (npm): `sha512-V6K3C8EBdEsPFZXSKMJe6ppQOENxuHARr9GvHX4hh47lAbhMRD9qf4oEK7LoaRQxULMa80/qt5gHO73aCleBBg==`
- License: MIT

```
The MIT License (MIT)

Copyright (c) 2014 - 2022 Knut Sveidqvist

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Libraries bundled inside `mermaid.min.js`

The minified file is a single bundle that also contains Mermaid's runtime dependencies. Their license headers are
kept in the file's `Bundled license information` comments. The main ones and their licenses:

| Library | License |
|---|---|
| d3, d3-* modules | ISC |
| d3-sankey | BSD-3-Clause |
| DOMPurify | MPL-2.0 OR Apache-2.0 (used under Apache-2.0) |
| chevrotain | Apache-2.0 |
| KaTeX, cytoscape, cytoscape-cose-bilkent, cytoscape-fcose, cose-base, layout-base | MIT |
| dagre-d3-es, khroma, marked, stylis, roughjs, dayjs, es-toolkit, lodash-es | MIT |
| @mermaid-js/parser, langium, @iconify/utils, @braintree/sanitize-url, @upsetjs/venn.js, uuid, ts-dedent | MIT |

## KaTeX 0.18.11

- Files: `src/main/resources/pl/mdreview/katex/katex.min.js`, `katex.min.css` and `fonts/*.woff2` (the `dist` files of
  the npm package `katex@0.18.11`, unmodified; the plugin inlines the fonts into the stylesheet as `data:` URIs when
  it builds a page), license text in `src/main/resources/pl/mdreview/katex/LICENSE`.
- Source: https://github.com/KaTeX/KaTeX
- Integrity (npm): `sha512-yvyz/2VMKqoho/35Hat5w37wIEdWJ5ElVMidTAlWrxr5uW4aBGsEpveTmKh18/zw08v/7lGswCPon4OhqmqrTA==`
- License: MIT (the fonts are part of the package under the same license)

```
The MIT License (MIT)

Copyright (c) 2013-2020 Khan Academy and other contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
