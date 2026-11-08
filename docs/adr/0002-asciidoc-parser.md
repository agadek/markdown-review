# ADR 0002: Parse AsciiDoc with AsciidoctorJ

Status: accepted (P4.1 confirmed), phase 4.

## Context

FR-16 to FR-24 ask for the Markdown review experience on AsciiDoc files. The diff engine compares a flat list of
blocks, each with a key, a plain text for the word diff and a source line range (ENGINE_CONTRACT). For Markdown,
commonmark-java gives all three: a tree of block nodes with source spans and inline nodes to render.

For AsciiDoc the parser must:

1. find the block structure as Asciidoctor sees it (sections, delimited blocks, lists with continuations, tables,
   admonitions), because a reviewer expects the diff to match what their site or PDF build shows;
2. give a source line for every block, for navigation (FR-14), revert and apply (FR-12) and block editing (FR-13);
3. convert inline markup (formatting, links, cross-references, attribute references, passthroughs) to HTML;
4. run inside `MarkdownDiffRenderer.render()`: off the EDT, concurrently, with no IntelliJ APIs and no I/O.

AsciiDoc has no formal grammar; the language is defined by Asciidoctor's behavior (the AsciiDoc Language
specification at Eclipse is in progress and has no complete parser). A second implementation would drift from
Asciidoctor on exactly the corner cases a reviewer notices.

## Options considered

- **AsciidoctorJ** (chosen): the Java binding of Asciidoctor, running the Ruby implementation on JRuby. `load()`
  returns the document tree (`Document`, `Section`, `Block`, `List`, `ListItem`, `DescriptionList`, `Table`, ...)
  without converting the whole document; with the `sourcemap` option every structural node has a
  `getSourceLocation()` with its first line. Inline content is converted on request (`getContent()`, `getText()`,
  `getTitle()`). It is the reference implementation, so the block structure is right by definition. Costs: JRuby's
  start-up (seconds, once per IDE session) and memory (one Ruby runtime), and no end line per block.
- **Asciidoctor.js in JCEF**: the same implementation transpiled to JavaScript, run in the viewer's browser. It
  would move parsing out of `render()` into the page, which breaks the engine contract (the diff needs blocks before
  the page exists), makes the engine untestable without a browser, and makes export and multi-file export depend on
  page scripts. Running it in a JVM JavaScript engine instead would need GraalJS, which the IDE does not ship.
- **The AsciiDoc plugin's PSI** (lexer and parser of the JetBrains AsciiDoc plugin): accurate source offsets, but it
  is an IntelliJ API (needs a project, read actions, PSI files), which the core may not use, its tree is a syntax
  tree rather than Asciidoctor's model (no attribute substitution, no converted inline HTML), and it is not a public
  API of that plugin.
- **A parser of our own** for a subset: small and fast, but it would be a second AsciiDoc implementation, wrong on
  continuations, nested delimiters, attribute substitution and every other place where the language is defined by
  Asciidoctor's code. Every bug report would start with "Asciidoctor renders it differently".

## Decision

Parse with AsciidoctorJ:

- `asciidoctor.load(text, options)` with `sourcemap: true`, `safe: SECURE`, `parse_header_only: false`, no base
  directory and no `to_file`, so nothing is read or written (NFR-13).
- One `Asciidoctor` instance per IDE session, created lazily on the first AsciiDoc render and reused. The AsciiDoc
  front end receives it from the IDE layer through a small factory interface, so the core does not know where the
  library comes from ([ADR 0003](0003-asciidoctorj-from-asciidoc-plugin.md)).
- The document tree is walked by the AsciiDoc front end ([ADR 0005](0005-asciidoc-block-model.md)); Asciidoctor
  converts only inline content. Block markup is ours ([ADR 0006](0006-asciidoc-rendering-reuse.md)).
- End lines, the document header, comments and `include::` lines, which the tree does not give, come from a line
  scanner over the source that uses the tree's start lines as anchors.

P4.1 found that one instance can `load` and convert from two threads at once, so calls are not serialized. If a
future AsciidoctorJ version regresses, calls are serialized behind a lock in the front end; `render()` stays callable
concurrently, and results that arrive after the input changed are already discarded by the IDE layer.

## Spike results (P4.1)

Measured on macOS 15.7.7 (Apple Silicon), Oracle JDK 21.0.9, 2 GB heap, with AsciidoctorJ 3.0.0 (the version in
AsciiDoc plugin 0.44.10) and 3.0.1 (0.45.11):

- First `Asciidoctor.Factory.create()`: 1.57–2.14 s and roughly 37–57 MB extra used heap. The first `load` of a
  1,015-line document took 236–266 ms and left about 49–68 MB of used heap. The first AsciiDoc render therefore
  starts Asciidoctor well inside NFR-12's 5 s; the 10,000-line first load was not measured separately, but its
  steady-state load plus conversion is below 300 ms.
- Steady state, median of 10 after two warm-up loads: 1,015 lines 46–52 ms load, 11–15 ms inline conversion,
  60–65 ms both; 10,003 lines 107–251 ms load, 31–45 ms conversion, 138–295 ms both. The 10,000-line spread is
  JIT and GC noise; the worst median is still far below NFR-12's 2 s.
- Two threads called `load` and converted every inline value on one instance, 1,000 times each, released together
  from a barrier: no exception and no wrong or cross-contaminated tree. This agrees with AsciidoctorJ's
  documentation, which describes Asciidoctor as thread-safe. One shared instance needs no lock.
- `shutdown()`/`close()` stops the Ruby runtime threads; without it a `Ruby-0-JIT-1` thread stays alive. Neither
  call makes the defining class loader reclaimable on JDK 21; that unload limitation is recorded in
  [ADR 0003](0003-asciidoctorj-from-asciidoc-plugin.md).

These are JVM used-heap numbers, not process RSS. The 2,791-line Markdown fixture `20-large-file` was not converted
to AsciiDoc; the 1,015- and 10,003-line generated samples cover the NFR-2 sizes, and no AsciiDoc fixture exists
before P4.3.

## Implementation (P4.3, P4.6)

Implemented as decided. `AsciiDocBlockSource` calls `load` with `sourcemap: true` and `SECURE`, no base directory and
no output file; `AsciiDocLines` supplies the header, attribute, comment, include, conditional and end-line data the
tree lacks. The front end is platform-free and reaches Asciidoctor only through `AsciidoctorProvider`. P4.1 found
concurrent `load` safe, so calls are not serialized; exceptions and `LinkageError` fall back to raw paragraphs for
that side. P4.6 wires the provider from the optional AsciiDoc plugin integration.

## Consequences

- The block structure, attribute substitution and inline conversion match Asciidoctor, so the rendered diff agrees
  with the reviewer's build.
- The first AsciiDoc diff in a session waits for JRuby (NFR-12); the IDE shows a notice instead of an empty viewer.
- Line ranges depend on the source map plus our scanner; P4.1 measured where the source map is missing or
  imprecise (conditional tail paragraphs, block titles, comments, includes, admonition paragraphs and table rows)
  and ADR 0005 lists the fallbacks.
- AsciidoctorJ's API (2.x/3.x) is stable, but its JRuby runtime is large; where it comes from is decided separately
  in [ADR 0003](0003-asciidoctorj-from-asciidoc-plugin.md).
