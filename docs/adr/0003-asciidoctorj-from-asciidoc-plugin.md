# ADR 0003: Take AsciidoctorJ from the JetBrains AsciiDoc plugin

Status: revised and accepted (P4.1: go, with a known class-loader unload limitation), phase 4.

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

- **Optional dependency on the AsciiDoc plugin, reuse its AsciidoctorJ** (chosen): no size cost and no third-party
  notices to add, though the first AsciiDoc diff starts **our own** JRuby runtime next to the AsciiDoc plugin's: the
  classes are shared, the runtime is not. AsciiDoc support exists only while that plugin is enabled (NFR-11), and our
  code runs against whichever AsciidoctorJ version that plugin ships, so we depend on its packaging and update
  cadence. We create our own `Asciidoctor` instance from its classes, we do not share its instance or call its
  internal API.
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
- The Gradle build adds the AsciiDoc plugin as a compile-time plugin dependency:
  `plugin("org.asciidoctor.intellij.asciidoc", "0.44.10")` for the oldest supported IDE. AsciidoctorJ is
  `compileOnly("org.asciidoctor:asciidoctorj:3.0.0")`; tests use the same version as `testImplementation`, so the
  core is unit-tested without the IDE (NFR-15). Nothing from either is bundled.
- Code that touches AsciidoctorJ classes lives in `pl.mdreview.engine.asciidoc` (the front end, platform-free) and
  `pl.mdreview.ide.asciidoc` (the factory and recognizer, loaded only through `markdown-review-withAsciiDoc.xml`). No
  class outside these packages imports `org.asciidoctor.*`, so with the AsciiDoc plugin absent none of them loads.
- `pl.mdreview.ide.asciidoc` registers an `AsciidoctorProvider` extension that creates one `Asciidoctor` instance
  lazily with `Asciidoctor::class.java.classLoader` as the thread context class loader (JRuby resolves its gems
  through it), and calls `shutdown()`/`close()` when the AsciiDoc plugin or ours unloads. Do not get the loader
  through `PluginDescriptor.getClassLoader()` (deprecated in 2026.1) or `PluginManagerCore.getPlugin(...)` (internal
  in 263); both fail `verifyPlugin` at the configured failure levels.
- The settings page shows "AsciiDoc diffs need the AsciiDoc plugin" with an install link while it is missing.

**Fallback if P4.1 fails** (the classes are not reachable, JRuby cannot start from our plugin's class loader,
`verifyPlugin` reports the dependency's library classes as internal, or a supported IDE version ships an
incompatible AsciidoctorJ): the companion-plugin option replaces this decision, with the same
`AsciidoctorProvider` extension point, so P4.2 to P4.5 do not change.

## Spike results (P4.1)

**Go.** The optional dependency resolves, compiles and verifies, and both AsciidoctorJ versions in the supported
IDE range produce the same tree and source lines. The unload behavior has a known limitation, recorded below.

### Coordinates and versions

- IC 2025.1.3 (build 251.26927.53): `plugin("org.asciidoctor.intellij.asciidoc", "0.44.10")` resolves to
  `com.jetbrains.plugins:org.asciidoctor.intellij.asciidoc:0.44.10`; its output is extracted and its bundled jars
  are on the compile classpath. 0.44.10 declares since-build 251.23536.34 and until-build 253.22441.33, and ships
  AsciidoctorJ 3.0.0 (Asciidoctor 2.0.23), asciidoctorj-api 3.0.0 and JRuby 9.4.8.0.
- IU 2026.1 (build 261.22158.277) and IU 263.7307.44: the verifier resolved
  `org.asciidoctor.intellij.asciidoc:0.45.11`, which declares since-build 253.25908 and no until-build, and ships
  AsciidoctorJ 3.0.1 (Asciidoctor 2.0.26), asciidoctorj-api 3.0.1 and JRuby 9.4.14.0. 0.45.11 cannot be installed
  on 2025.1, so the build pins 0.44.10 for the oldest IDE and compiles against AsciidoctorJ 3.0.0; 3.0.1 is
  binary compatible and was exercised with the same spike.
- `compileOnly("org.asciidoctor:asciidoctorj:3.0.0")` and
  `testImplementation("org.asciidoctor:asciidoctorj:3.0.0")` resolve from Maven Central. The plugin dependency
  alone already exposes the bundled AsciidoctorJ classes to `compileKotlin` (a build without `compileOnly` also
  compiles); `compileOnly` is kept to pin the API independently of the plugin version.
- The optional descriptor is `markdown-review-withAsciiDoc.xml`; `plugin.xml` declares
  `<depends optional="true" config-file="markdown-review-withAsciiDoc.xml">org.asciidoctor.intellij.asciidoc</depends>`.
  A spike action in `pl.mdreview.ide.asciidoc` compiled and was included in `verifyPlugin`; it was not run in a
  live IDE (`runIde` was not available in this environment).
- `verifyPlugin`, with the dependency:
  - IC 2025.1.3 and 0.44.10: Compatible.
  - IU 2026.1 and 0.45.11: Compatible; the verifier also says the plugin can probably be enabled or disabled
    without an IDE restart.
  - IU 263.7307.44 and 0.45.11: 8 compatibility problems, exactly the baseline's 8 with no AsciiDoc dependency
    (`ChangesUtil`, `CurrentContentRevision`, `VcsDataKeys` and `ProjectLevelVcsManager` unresolved), and no
    AsciiDoc-related problem. The full three-IDE run completes once the verification caches are warm.
- The optional dependency is not bundled: the spike plugin zip is 2,170,207 bytes against a 2,164,053-byte
  baseline (+6 KB), far below NFR-14's 1 MB.

### Class loader

- Use `Asciidoctor::class.java.classLoader` as the thread context class loader. It is the AsciiDoc plugin's
  defining loader and it needs no internal API. JRuby resolves its gems through it; the call works with
  AsciidoctorJ 3.0.0 and 3.0.1.
- Do not use `PluginDescriptor.getClassLoader()`: it is deprecated in 2026.1 and `verifyPlugin` fails
  `DEPRECATED_API_USAGES`. Do not use `PluginManagerCore.getPlugin(PluginId)`: it is internal in 263 and
  `verifyPlugin` fails `INTERNAL_API_USAGES`. Both were tried and removed; with
  `Asciidoctor::class.java.classLoader` the verifier reports no API problems.

### Unload

`Asciidoctor.shutdown()` and `close()` stop the runtime: after a shutdown no Ruby thread remains; without it a
`Ruby-0-JIT-1` thread stays alive. The class loader is different. In a `URLClassLoader` over the plugin's 41 jars,
after `shutdown()`, `close()`, dropping every reference, closing the loader and 40 explicit GCs, neither the loader
nor its classes were collected. The heap dump's root chains show `java.lang.invoke.LambdaForm` roots holding JRuby's
generated classes, and `ApplicationShutdownHooks` -> `JRubyClassLoader$1` -> inherited `AccessControlContext` ->
`ProtectionDomain` holding the loader. Pre-creating `$java.io.tmpdir/jruby-<pid>` removes the JRuby shutdown hook
(`JRubyClassLoader.getTempDir()` registers it only when it creates the directory) but not the other roots.
`jruby.native.enabled=false`, `jruby.compile.mode=OFF` and `jruby.compile.invokedynamic=false` did not release it.

A second probe modeled our plugin loader as a child of the plugin-jar loader and called Asciidoctor through a class
loaded by the child. That child loader was also retained, while a trivial child loader was collected: JRuby captures
the calling class's `ProtectionDomain` in its `AccessControlContext`. If our plugin creates the runtime directly, its
own class loader is therefore retained after a dynamic unload on JDK 21, even though the Ruby threads are gone.

The actual IDE disable/enable check was **not run**: this environment has no `runIde`/GUI session, and
`verifyPlugin` only reports static compatibility and "probably" dynamic eligibility. P4.6 must accept that one
runtime and its loader stay until IDE restart, or move the runtime into a long-lived loader or companion plugin and
keep our plugin loader out of the captured `ProtectionDomain`. If the platform's dynamic unload check reports the
leak and that is unacceptable, the companion-plugin fallback below replaces this decision; it still has to stay
loaded to avoid the same JRuby leak in its own loader.

## Implementation (P4.6)

Implemented as decided. `plugin.xml` declares the optional dependency and `markdown-review-withAsciiDoc.xml`
registers `PluginAsciidoctorProvider` as an application service plus the startup activity that installs it in
`AsciiDocSupport` and `CoreMarkdownDiffRenderer`. The provider creates one instance lazily with
`Asciidoctor::class.java.classLoader` as the thread context class loader and calls `shutdown()`/`close()` in
`dispose()`. The plugin zip stays at 2.2 MB with no AsciidoctorJ or Ruby classes, and `verifyPlugin` reports no
AsciiDoc-related problem. The known class-loader retention on dynamic unload remains as recorded above.

## Consequences

- Markdown Review's download does not grow (NFR-14), and Markdown users load nothing new.
- AsciiDoc support appears and disappears with the AsciiDoc plugin; the viewers' `canShow` answers `false` for
  AsciiDoc while it is missing (NFR-11).
- A new AsciiDoc plugin release can change the AsciidoctorJ version under us. 0.44.10 ships 3.0.0 (2025.1) and
  0.45.11 ships 3.0.1 (2026.1 and later); both produced the same tree and source lines, and 3.0.1 is binary
  compatible with the 3.0.0 API the code compiles against. CI runs the AsciiDoc fixtures against the AsciidoctorJ
  version of each IDE in the verification list, and the plugin catches `LinkageError` from the front end, logs it
  once and falls back to the text viewer for that request.
- We start our own JRuby runtime next to the AsciiDoc plugin's. Memory doubles for the Ruby side while both are in
  use (about 37–57 MB of extra used heap for the runtime in the P4.1 measurement); sharing the AsciiDoc plugin's
  instance would need its internal API and is not done.
- `shutdown()`/`close()` stops the Ruby threads but does not release the defining class loader on JDK 21. A dynamic
  disable/enable of our plugin after an AsciiDoc render therefore retains one runtime and its loader until IDE
  restart. This is a known limitation, not a reason to fail the dependency itself; see "Unload" above.
