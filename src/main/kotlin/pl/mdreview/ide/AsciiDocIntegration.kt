package pl.mdreview.ide

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import pl.mdreview.engine.asciidoc.AsciidoctorProvider
import pl.mdreview.render.CoreMarkdownDiffRenderer

/**
 * Installs and removes the optional AsciiDoc support without importing any AsciidoctorJ class.
 *
 * The provider lives in `pl.mdreview.ide.asciidoc` and is declared in `markdown-review-withAsciiDoc.xml`; this
 * object reaches it by name, so the main plugin class loader never resolves AsciidoctorJ while the AsciiDoc plugin
 * is disabled (NFR-11).
 */
object AsciiDocIntegration {

    const val PLUGIN_ID = "org.asciidoctor.intellij.asciidoc"

    private const val PROVIDER_CLASS = "pl.mdreview.ide.asciidoc.PluginAsciidoctorProvider"

    @Volatile
    private var warnedAboutRestart = false

    /**
     * Makes AsciiDoc diffs available. Called by the optional startup activity and by
     * [AsciiDocPluginListener] when the AsciiDoc plugin is enabled at runtime. Idempotent.
     *
     * @param notifyOnFailure ask for an IDE restart when the plugin was enabled dynamically but its classes cannot
     * be reached. At startup a missing optional descriptor is normal and is not reported.
     */
    fun enable(notifyOnFailure: Boolean = false) {
        if (AsciiDocSupport.isAvailable()) return
        val runtime = try {
            val providerClass = Class.forName(PROVIDER_CLASS, true, AsciiDocIntegration::class.java.classLoader)
            ApplicationManager.getApplication().getService(providerClass)
        } catch (_: Throwable) {
            // The optional descriptor is not loaded, or its classes are not reachable yet.
            null
        }
        if (runtime !is AsciiDocSupport.Runtime) {
            if (notifyOnFailure) askForRestart()
            return
        }
        warnedAboutRestart = false
        AsciiDocSupport.install(runtime)
        (runtime as? AsciidoctorProvider)?.let(CoreMarkdownDiffRenderer::installAsciidoctorProvider)
    }

    /** Called when the AsciiDoc plugin is disabled or unloaded; AsciiDoc diffs fall back to the text viewer. */
    fun disable() {
        AsciiDocSupport.uninstall()
        CoreMarkdownDiffRenderer.uninstallAsciidoctorProvider()
    }

    /**
     * The dynamic class loader may not see AsciidoctorJ until the IDE restarts. Say so instead of leaving AsciiDoc
     * silently unavailable; repeated plugin toggles show it once.
     */
    private fun askForRestart() {
        if (warnedAboutRestart) return
        warnedAboutRestart = true
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Markdown Review")
            .createNotification(
                MarkdownReviewBundle.message("asciidoc.restart.title"),
                MarkdownReviewBundle.message("asciidoc.restart.message"),
                NotificationType.WARNING,
            )
            .notify(null)
    }
}
