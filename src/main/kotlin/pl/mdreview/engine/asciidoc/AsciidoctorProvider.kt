package pl.mdreview.engine.asciidoc

/**
 * Supplies the Asciidoctor instance the AsciiDoc front end parses with.
 *
 * The IDE layer implements this with the AsciidoctorJ classes of the JetBrains AsciiDoc plugin and creates one
 * instance lazily per session (ADR 0003). Tests implement it with `Asciidoctor.Factory.create()`. The engine only
 * sees this interface, so no class outside [pl.mdreview.engine.asciidoc] imports `org.asciidoctor.*`.
 */
interface AsciidoctorProvider {
    fun asciidoctor(): org.asciidoctor.Asciidoctor
}
