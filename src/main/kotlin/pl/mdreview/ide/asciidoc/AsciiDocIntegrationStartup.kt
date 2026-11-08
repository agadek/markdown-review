package pl.mdreview.ide.asciidoc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import pl.mdreview.ide.AsciiDocIntegration

/**
 * Makes AsciiDoc diffs available when the optional AsciiDoc plugin is enabled.
 *
 * Runs only from `markdown-review-withAsciiDoc.xml`, so it never loads without that plugin. Asciidoctor itself is
 * still created lazily by [PluginAsciidoctorProvider] on the first AsciiDoc diff (NFR-12). Enabling the AsciiDoc
 * plugin while the IDE runs is handled by `AsciiDocPluginListener` instead.
 */
class AsciiDocIntegrationStartup : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        // Headless tests install the runtime explicitly when they exercise AsciiDoc support.
        if (ApplicationManager.getApplication().isUnitTestMode) return
        AsciiDocIntegration.enable()
    }
}
