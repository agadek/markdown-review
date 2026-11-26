package pl.mdreview.ide.asciidoc

import pl.mdreview.render.CoreMarkdownDiffRenderer
import java.io.File
import java.net.URI
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Keeps AsciidoctorJ out of the classes reachable from the main plugin descriptor.
 *
 * AsciidoctorJ comes from the optional AsciiDoc plugin (ADR 0003). When JetBrains Marketplace cannot resolve that
 * plugin for an IDE build, the Plugin Verifier skips only the classes reachable from
 * `markdown-review-withAsciiDoc.xml` alone; any `org.asciidoctor` reference in a class that `plugin.xml` can reach is
 * reported as a binary incompatibility (1.4.1 against IU-263.8311.30). So only the classes below may name
 * AsciidoctorJ, `AsciiDocBlockSource`, `AsciidoctorProvider` or the `pl.mdreview.ide.asciidoc` package, and code
 * outside them reaches that package by class name only.
 */
class AsciidoctorReachabilityTest {

    /** Internal class name prefixes that only the optional AsciiDoc descriptor may reach. */
    private val optionalOnly = listOf(
        "pl/mdreview/ide/asciidoc/",
        "pl/mdreview/engine/asciidoc/AsciiDocBlockSource",
        "pl/mdreview/engine/asciidoc/AsciidoctorProvider",
    )

    private val forbidden = listOf("org/asciidoctor/") + optionalOnly

    @Test
    fun `only the optional AsciiDoc classes name AsciidoctorJ`() {
        val classes = mainClasses()
        assertTrue(classes.keys.any { it.startsWith("pl/mdreview/ide/asciidoc/PluginAsciidoctorProvider") }, "main classes not found")
        val offenders = classes
            .filterKeys { name -> optionalOnly.none(name::startsWith) }
            .mapValues { (_, bytes) -> String(bytes, Charsets.ISO_8859_1).let { text -> forbidden.filter(text::contains) } }
            .filterValues { it.isNotEmpty() }
        assertEquals(emptyMap(), offenders, "classes reachable from plugin.xml must not name these")
    }

    /** Compiled main classes by internal name (`pl/mdreview/...`), from a directory or a jar. */
    private fun mainClasses(): Map<String, ByteArray> {
        // The IDE test class loader gives no code source, so find the output from the class file's own URL.
        val anchor = CoreMarkdownDiffRenderer::class.java.name.replace('.', '/') + ".class"
        val url = assertNotNull(CoreMarkdownDiffRenderer::class.java.classLoader.getResource(anchor), "no $anchor")
        return if (url.protocol == "file") {
            val path = File(url.toURI()).invariantSeparatorsPath
            val root = File(path.removeSuffix(anchor))
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .associate { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class") to it.readBytes() }
        } else {
            // jar:file:/.../markdown-review.jar!/pl/mdreview/render/CoreMarkdownDiffRenderer.class
            val jar = File(URI(url.toString().removePrefix("jar:").substringBefore("!/")))
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .associate { it.name.removeSuffix(".class") to zip.getInputStream(it).readBytes() }
            }
        }
    }
}
