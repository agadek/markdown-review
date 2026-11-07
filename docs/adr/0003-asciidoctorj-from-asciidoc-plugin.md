# ADR 0003: Take AsciidoctorJ from the JetBrains AsciiDoc plugin

Status: proposed (P4.1 confirms or switches to the fallback), phase 4.

## Context

[ADR 0002](0002-asciidoc-parser.md) picks AsciidoctorJ. AsciidoctorJ runs on JRuby, and together they are tens of
megabytes, several times the size of the current plugin, which bundles only commonmark-java, java-diff-utils,
Mermaid and KaTeX. Most users of Markdown Review never open an AsciiDoc diff (NFR-14: Markdown users must not pay for
AsciiDoc).

The JetBrains AsciiDoc plugin (`org.asciidoctor.intellij.asciidoc`, open source, maintained by the Asciidoctor
community) already bundles AsciidoctorJ and JRuby for its preview. Anyone who edits AsciiDoc in a JetBrains IDE
almost certainly has it installed: without it an `.adoc` file is plain text with no file type, highlighting or
preview.

The IntelliJ Platform gives a plugin that declares `<depends optional="true">` on another plugin access to that
plugin's classes and bundled libraries, through the dependency's class loader, but only while the dependency is
installed and enabled. This is how `markdown-review-withMarkdown.xml` already uses the bundled Markdown plugin.

## Options considered

- **Optional dependency on the AsciiDoc plugin, reuse its AsciidoctorJ** (chosen): no size cost, no second JRuby in
  memory next to the AsciiDoc plugin's own, no third-party notices to add. AsciiDoc support exists only while that
  plugin is enabled (NFR-11), and our code runs against whichever AsciidoctorJ version that plugin ships, so we
  depend on its packaging and update cadence. We create our own `Asciidoctor` instance from its classes, we do not
  share its instance or call its internal API.
- **Bundle AsciidoctorJ in Markdown Review**: independent of another plugin, one AsciidoctorJ version we test
  against. Every user downloads and installs tens of megabytes for a feature most never use, the plugin's
  third-party notices grow (Asciidoctor MIT, AsciidoctorJ Apache-2.0, JRuby EPL/GPL/LGPL), and users with the
  AsciiDoc plugin load two JRuby copies.
- **A companion plugin "Markdown Review: AsciiDoc" that bundles AsciidoctorJ** and depends on Markdown Review: no
  cost for Markdown users and no dependency on the AsciiDoc plugin, but two plugins to release and keep in step, a
  public extension point between them, and the same double JRuby for AsciiDoc plugin users.
- **Download AsciidoctorJ on first use** (as the AsciiDoc plugin does for Asciidoctor PDF): small plugin, but it
  breaks NFR-8's "no network access" promise and needs its own integrity checks and update path.

## Decision

- `plugin.xml` declares `<depends optional="true" config-file="markdown-review-withAsciiDoc.xml">org.asciidoctor.intellij.asciidoc</depends>`.
- The Gradle build adds the AsciiDoc plugin as a compile-time plugin dependency (`plugin("org.asciidoctor.intellij.asciidoc", <version>)`
  for the oldest supported IDE) and AsciidoctorJ as `compileOnly`; tests use AsciidoctorJ as `testImplementation`, so
  the core is unit-tested without the IDE (NFR-15). Nothing from either is bundled.
- Code that touches AsciidoctorJ classes lives in `pl.mdreview.engine.asciidoc` (the front end, platform-free) and
  `pl.mdreview.ide.asciidoc` (the factory and recognizer, loaded only through `markdown-review-withAsciiDoc.xml`). No
  class outside these packages imports `org.asciidoctor.*`, so with the AsciiDoc plugin absent none of them loads.
- `pl.mdreview.ide.asciidoc` registers an `AsciidoctorProvider` extension that creates one `Asciidoctor` instance
  lazily with the AsciiDoc plugin's class loader as the thread context class loader (JRuby resolves its gems through
  it), and closes it when the AsciiDoc plugin or ours unloads.
- The settings page shows "AsciiDoc diffs need the AsciiDoc plugin" with an install link while it is missing.

**Fallback if P4.1 fails** (the classes are not reachable, JRuby cannot start from our plugin's class loader,
`verifyPlugin` reports the dependency's library classes as internal, or a supported IDE version ships an
incompatible AsciidoctorJ): the companion-plugin option replaces this decision, with the same
`AsciidoctorProvider` extension point, so P4.2 to P4.5 do not change.

## Consequences

- Markdown Review's download does not grow (NFR-14), and Markdown users load nothing new.
- AsciiDoc support appears and disappears with the AsciiDoc plugin; the viewers' `canShow` answers `false` for
  AsciiDoc while it is missing (NFR-11).
- A new AsciiDoc plugin release can change the AsciidoctorJ version under us. AsciidoctorJ's public API has been
  stable across 2.x and 3.x; CI runs the AsciiDoc fixtures against the AsciidoctorJ version of each IDE in the
  verification list, and the plugin catches `LinkageError` from the front end, logs it once and falls back to the
  text viewer for that request.
- We start our own JRuby runtime next to the AsciiDoc plugin's. Memory doubles for the Ruby side while both are in
  use; sharing the AsciiDoc plugin's instance would need its internal API and is not done.
