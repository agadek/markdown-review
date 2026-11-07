package pl.mdreview.ide.asciidoc

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pl.mdreview.api.DocumentFormat
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.ViewMode
import pl.mdreview.ide.AsciiDocSupport
import pl.mdreview.render.CoreMarkdownDiffRenderer
import kotlin.test.assertContains

/**
 * Exercises the real AsciidoctorJ instance the optional AsciiDoc descriptor provides, without `runIde`.
 * Skipped when the AsciiDoc plugin is not part of this test environment.
 */
class PluginAsciidoctorProviderTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            AsciiDocSupport.uninstall()
            CoreMarkdownDiffRenderer.uninstallAsciidoctorProvider()
        } finally {
            super.tearDown()
        }
    }

    fun `test the plugin Asciidoctor renders an AsciiDoc diff`() {
        if (PluginManagerCore.getPlugin(PluginId.getId("org.asciidoctor.intellij.asciidoc")) == null) return
        val provider = PluginAsciidoctorProvider()
        try {
            AsciiDocSupport.install(provider)
            CoreMarkdownDiffRenderer.installAsciidoctorProvider(provider)
            val result = CoreMarkdownDiffRenderer().render(
                RenderRequest(
                    "= Title\n\nOld paragraph.\n",
                    "= Title\n\nNew paragraph.\n",
                    ViewMode.SIDE_BY_SIDE,
                    format = DocumentFormat.ASCIIDOC,
                ),
            )
            assertContains(result.bodyHtml, "<h1>Title</h1>")
            assertContains(result.bodyHtml, "mdr-del")
            assertContains(result.bodyHtml, "mdr-ins")
        } finally {
            provider.dispose()
        }
    }
}
