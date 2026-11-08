# ADR 0004: One format-neutral engine for Markdown and AsciiDoc

Status: accepted, phase 4.

## Context

The core has three layers:

1. `BlockExtractor` parses Markdown with commonmark-java and flattens it into `Block`s;
2. `MarkdownDiffEngine` diffs two block lists: block-level Myers diff on keys, pairing of removed and added blocks
   into modified ones by word similarity, word diff on `plainText`, moved-block linking;
3. `DiffHtmlRenderer`, `InlineMarks`, `LinkChanges` and `EditRegions` turn the diff into `bodyHtml` and `BlockInfo`s.

Layer 2 is already format-neutral: it reads only `Block.kind`, `key`, `plainText`, `atoms` and `context`. Layers 1
and 3 are tied to commonmark: `Block.node` and `Block.renderNodes` are commonmark `Node`s, and the renderer applies
word marks and link changes while walking those nodes. HTML blocks are the exception: they are rendered from
`HtmlFragment` parts with `markedHtml(parts, marks)`, which applies the same marks to HTML text runs.

The public contract, `MarkdownDiffRenderer.render(RenderRequest)`, takes only texts and options; it does not say what
the texts are.

## Options considered

- **A second, separate AsciiDoc pipeline** (its own engine, renderer and `RenderedDiff`): no risk to Markdown, but
  every feature (pairing threshold, moves, collapse, edit regions, export, the page's DOM contract) would be built and
  fixed twice.
- **Convert AsciiDoc to Markdown and reuse everything**: lossy (admonitions, block titles, description lists, callouts,
  table spans) and line numbers would point into a text the user never sees.
- **One engine with a front end per format** (chosen): the diff engine and HTML generator stay single; a format only
  supplies blocks and their content.

## Decision

- `pl.mdreview.api.DocumentFormat { MARKDOWN, ASCIIDOC }` and `RenderRequest.format: DocumentFormat = MARKDOWN`.
  Additive, so existing callers and tests do not change. Both sides of one request have the same format
  ([ADR 0007](0007-asciidoc-ide-integration.md)).
- `MarkdownDiffRenderer` keeps its name and its single `render()`; renaming the public interface and the
  `META-INF/services` file would break the contract for no benefit. Its KDoc says "Markdown or AsciiDoc".
- A front end per format behind `interface BlockSource { fun extract(text: String, options: DiffOptions): List<Block> }`:
  `MarkdownBlockSource` (today's `BlockExtractor`) and `AsciiDocBlockSource` ([ADR 0005](0005-asciidoc-block-model.md)).
  `MarkdownDiffEngine` is renamed `DiffEngine` and takes block lists only; `CoreMarkdownDiffRenderer` picks the
  `BlockSource` from `request.format`.
- `Block.node` / `Block.renderNodes` become `Block.content: BlockContent`:
  - `BlockContent.Commonmark(nodes: List<Node>)` for Markdown, rendered as today;
  - `BlockContent.Html(parts: List<HtmlFragment.Part>)`: sanitized markup and text runs whose concatenated text is
    exactly `Block.plainText`, rendered with `markedHtml` (generalized from HTML blocks);
  - `BlockContent.Raw` for code lines and other blocks rendered from `rawText`.
- `BlockKind` stays one enum. AsciiDoc reuses the existing kinds where the meaning is the same (`HEADING`,
  `PARAGRAPH`, `LIST_ITEM`, `TABLE_ROW`, `CODE_FENCE`, `CODE_LINE`, `THEMATIC_BREAK`, `ALERT`, `MERMAID`, `MATH`,
  `HTML_COMMENT` for comments) and adds only what Markdown has no equivalent for (see ADR 0005). Kinds are compared
  as they are today: blocks of different kinds never pair.
- `BlockContext` gains a format-neutral `containers: List<Container>` (kind, optional title, optional type) for
  delimited blocks; `quoteDepth` and `alert` stay for Markdown and are derived from it where they mean the same.
- `LinkChanges` gets an HTML variant that pairs `<a href>` / `<img src>` in `BlockContent.Html` by text and occurrence,
  like the commonmark variant does with `Link` / `Image` nodes, and produces the same `mdr-link-changed` /
  `mdr-img-changed` marks.
- The commonmark parser stays out of the AsciiDoc path and AsciidoctorJ out of the Markdown path.

## Implementation (P4.2–P4.6)

Implemented as decided. `DocumentFormat` and `RenderRequest.format` (default `MARKDOWN`) landed in P4.2;
`BlockExtractor` became `MarkdownBlockSource`, `MarkdownDiffEngine` became `DiffEngine(options, source)`, and
`Block.content` is `BlockContent.Commonmark`, `BlockContent.Html` or `BlockContent.Raw`. The HTML variant of
`LinkChanges`, `BlockContext.containers` and `RenderedDiff.format` (so the block editor can say "Markdown" or
"AsciiDoc") are in use. P4.3 added `AsciiDocBlockSource` and P4.6 registers it through the optional
`AsciidoctorProvider` integration.

The P4.2 gate held: every Markdown fixture and test passed with unchanged expectations, and the benchmark reported
8 ms / 6 ms for 1,000 lines and 90 ms / 79 ms for 10,000 lines (side by side / merged). The P4.6 plugin zip stayed at
2.2 MB and contains no AsciidoctorJ or Ruby classes; `verifyPlugin` is compatible on IC 2025.1.3 and IU 2026.1.

## Consequences

- AsciiDoc gets the pairing threshold, moved blocks, collapsing, `details` folding, navigation and `BlockInfo`
  semantics without new code in the diff engine.
- P4.2 is a refactoring of shipped code. It is done before any AsciiDoc code lands, and its gate is that every
  Markdown fixture and test passes with unchanged expectations and the benchmark stays within 5% (NFR-14).
- The DOM contract (`data-idx`, `blk-*`, `data-side`, `data-line`) and `CLASSES.md` do not change shape, so the page
  script, block actions, block menu and export work on AsciiDoc output unchanged.
- A later format (reStructuredText) would need only a `BlockSource` and its kinds; this ADR does not plan one.
