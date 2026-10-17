package pl.mdreview.ide

import com.intellij.openapi.diagnostic.logger
import pl.mdreview.api.MarkdownDiffRenderer
import java.util.ServiceLoader

/** Finds the core's [MarkdownDiffRenderer] through `META-INF/services`, or falls back to [FallbackRenderer]. */
object RendererProvider {
    val renderer: MarkdownDiffRenderer by lazy {
        val found = ServiceLoader.load(MarkdownDiffRenderer::class.java, MarkdownDiffRenderer::class.java.classLoader).firstOrNull()
        if (found == null) logger<RendererProvider>().info("No MarkdownDiffRenderer registered, using FallbackRenderer")
        found ?: FallbackRenderer()
    }
}
