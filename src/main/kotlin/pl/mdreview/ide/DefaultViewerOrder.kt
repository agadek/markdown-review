package pl.mdreview.ide

import com.intellij.diff.impl.DiffSettingsHolder
import com.intellij.diff.impl.DiffSettingsHolder.DiffSettings
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffPlaces
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Makes the [DefaultViewer] setting decide which viewer a diff window opens with.
 *
 * IntelliJ picks the first viewer in its per-place tool order (`DiffSettings.diffToolsOrder`, one list of
 * class names per diff window kind) that can show the request; tools missing from the list follow in
 * registration order, and plugin tools come before the built-in text viewers. Choosing a viewer in a diff
 * window moves it to the top of that place's list, so the choice is remembered per window kind.
 * See `docs/adr/0001-default-diff-viewer.md`.
 *
 * Our viewers accept only Markdown requests, so putting one first changes nothing for other files.
 */
object DefaultViewerOrder {

    /** Diff window kinds whose order is rewritten even when the user has not opened them yet. */
    val KNOWN_PLACES = listOf(
        DiffPlaces.DEFAULT,
        DiffPlaces.CHANGES_VIEW,
        DiffPlaces.COMMIT_DIALOG,
        DiffPlaces.VCS_LOG_VIEW,
        DiffPlaces.VCS_FILE_HISTORY_VIEW,
        DiffPlaces.SHELVE_VIEW,
        DiffPlaces.BLANK,
        DiffPlaces.EXTERNAL,
    )

    /**
     * The diff window kind of **Show Rendered Diff** ([ShowRenderedDiffAction]). Its own place, so opening it rendered
     * does not change what the other windows open with.
     */
    const val RENDERED_DIFF_PLACE = "MarkdownReview.RenderedDiff"

    private val RENDERED_TOOLS = listOf(RenderedSideBySideTool::class.java, RenderedMergedTool::class.java)
        .map { it.canonicalName }
    private val TEXT_TOOLS = listOf(SimpleDiffTool::class.java, UnifiedDiffTool::class.java)
        .map { it.canonicalName }

    /** Applies the setting if it changed since it was last written into IntelliJ's diff settings. */
    fun syncWithSettings(settings: MarkdownReviewSettings = MarkdownReviewSettings.getInstance()) {
        if (settings.appliedDefaultViewer == settings.defaultViewer) return
        apply(settings.defaultViewer)
        settings.appliedDefaultViewer = settings.defaultViewer
    }

    /** Rewrites the tool order of every known and every already used diff place for [viewer]. */
    fun apply(viewer: DefaultViewer) {
        val places = KNOWN_PLACES.toMutableSet()
        ApplicationManager.getApplication().getService(DiffSettingsHolder::class.java)
            ?.state?.PLACES_MAP?.keys?.let(places::addAll)
        for (place in places) {
            val settings = DiffSettings.getSettings(place)
            settings.diffToolsOrder = reorder(settings.diffToolsOrder, viewer)
        }
    }

    /** New order for one place: [viewer] first, everything else in its current relative order. */
    fun reorder(current: List<String>, viewer: DefaultViewer): List<String> = when (viewer) {
        DefaultViewer.RENDERED_SIDE_BY_SIDE -> moveToFront(current, RENDERED_TOOLS[0])
        DefaultViewer.RENDERED_MERGED -> moveToFront(current, RENDERED_TOOLS[1])
        DefaultViewer.TEXT -> {
            // Unlisted tools follow in registration order, where plugin tools come first, so a text viewer
            // must be listed before ours for it to win.
            val rest = current - RENDERED_TOOLS.toSet()
            val withText = if (rest.any { it in TEXT_TOOLS }) rest else listOf(TEXT_TOOLS[0]) + rest
            withText + RENDERED_TOOLS
        }
    }

    /**
     * Puts a rendered viewer first at [RENDERED_DIFF_PLACE] before the window opens: the one last chosen there, or
     * the merged viewer if that is the default viewer setting, otherwise side by side. A text viewer chosen in that
     * window is remembered as usual but does not outlast the window.
     */
    fun preferRendered(settings: MarkdownReviewSettings = MarkdownReviewSettings.getInstance()) {
        val place = DiffSettings.getSettings(RENDERED_DIFF_PLACE)
        place.diffToolsOrder = preferRendered(place.diffToolsOrder, settings.defaultViewer)
    }

    /** New order for [RENDERED_DIFF_PLACE]: the first rendered viewer in [current], or one picked by [viewer], first. */
    fun preferRendered(current: List<String>, viewer: DefaultViewer): List<String> {
        val tool = current.firstOrNull { it in RENDERED_TOOLS }
            ?: RENDERED_TOOLS[if (viewer == DefaultViewer.RENDERED_MERGED) 1 else 0]
        return moveToFront(current, tool)
    }

    private fun moveToFront(current: List<String>, tool: String) = listOf(tool) + current.filter { it != tool }
}

/** Applies the default viewer on the first start after installation or an update. */
class DefaultViewerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater { DefaultViewerOrder.syncWithSettings() }
    }
}
