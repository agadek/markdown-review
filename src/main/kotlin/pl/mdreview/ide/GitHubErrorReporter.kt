package pl.mdreview.ide

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.Consumer
import java.awt.Component
import java.net.URLEncoder

/**
 * "Report to Markdown Review" in the IDE's error dialog (P3.1): opens a new GitHub issue from the crash report
 * template in the browser, prefilled with the exception and the environment. Nothing is sent anywhere until the
 * user submits the issue on GitHub, where they can edit it first.
 */
class GitHubErrorReporter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = MarkdownReviewBundle.message("error.report.action")

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val event = events.firstOrNull() ?: return false
        val url = CrashReport.url(event.throwableText, additionalInfo, environment())
        BrowserUtil.browse(url)
        consumer.consume(SubmittedReportInfo(null, null, SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }

    private fun environment(): String {
        val app = ApplicationInfo.getInstance()
        val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"
        return listOf(
            "IDE: ${app.fullApplicationName} (build ${app.build.asString()})",
            "Plugin: $plugin",
            "OS: ${SystemInfo.getOsNameAndVersion()}",
            "JCEF: ${if (JBCefApp.isSupported()) "supported" else "not supported"}",
        ).joinToString("\n")
    }

    private companion object {
        const val PLUGIN_ID = "pl.mdreview.markdown-review"
    }
}

/** The GitHub "new issue" link for a crash, with the crash report template's fields filled in. */
internal object CrashReport {
    private const val NEW_ISSUE = "https://github.com/agadek/markdown-review/issues/new"

    /** GitHub refuses longer links, so the exception is cut to fit. */
    const val MAX_URL_LENGTH = 8000
    private const val MAX_TITLE_LENGTH = 120
    const val TRUNCATED = "\n... (truncated)"

    fun url(throwableText: String, additionalInfo: String?, environment: String, home: String? = System.getProperty("user.home")): String {
        val trace = hideHome(throwableText.trim(), home)
        val title = "Crash: " + trace.lineSequence().firstOrNull().orEmpty().let {
            if (it.length > MAX_TITLE_LENGTH) it.take(MAX_TITLE_LENGTH - 3) + "..." else it
        }
        fun build(stacktrace: String) = NEW_ISSUE + "?template=crash_report.yml" +
            "&title=" + encode(title) +
            "&steps=" + encode(hideHome(additionalInfo.orEmpty().trim(), home)) +
            "&environment=" + encode(environment) +
            "&stacktrace=" + encode(stacktrace)
        val full = build(trace)
        if (full.length <= MAX_URL_LENGTH) return full
        // Keep whole lines from the top, where the exception and the plugin's frames are.
        var kept = trace
        while (kept.isNotEmpty()) {
            kept = kept.substringBeforeLast('\n', "")
            val url = build(kept + TRUNCATED)
            if (url.length <= MAX_URL_LENGTH) return url
        }
        return build(TRUNCATED.trim())
    }

    /** The user's home directory as `~`, so file paths in messages do not show the user name. */
    private fun hideHome(text: String, home: String?): String =
        if (home.isNullOrBlank() || home == "/") text else text.replace(home, "~")

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
}
