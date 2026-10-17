package pl.mdreview.ide

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import pl.mdreview.api.DiffOptions

/** Viewer that the diff window shows first for a Markdown file (FR-11). */
enum class DefaultViewer(val messageKey: String) {
    RENDERED_SIDE_BY_SIDE("settings.viewer.side.by.side"),
    RENDERED_MERGED("settings.viewer.merged"),
    TEXT("settings.viewer.text"),
}

/**
 * Application-wide plugin settings (FR-11), edited on Settings | Tools | Markdown Review and by the
 * toggles in the diff window. Replaces the `PropertiesComponent` keys of version 0.1 and migrates their values.
 */
@Service(Service.Level.APP)
@State(name = "MarkdownReviewSettings", storages = [Storage("markdownReview.xml")])
class MarkdownReviewSettings : PersistentStateComponent<MarkdownReviewSettings.SettingsState> {

    class SettingsState {
        var defaultViewer: DefaultViewer = DefaultViewer.RENDERED_SIDE_BY_SIDE
        var ignoreWhitespace: Boolean = false
        var collapseUnchanged: Boolean = true
        var largeFileLines: Int = DEFAULT_LARGE_FILE_LINES
        var largeFileKilobytes: Int = DEFAULT_LARGE_FILE_KILOBYTES
        var loadRemoteImages: Boolean = false
        var renderMermaid: Boolean = true
        var showBlockActions: Boolean = true

        /** The [defaultViewer] last written into IntelliJ's diff tool order, see [DefaultViewerOrder]. */
        var appliedDefaultViewer: DefaultViewer? = null
    }

    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        this.state = state
    }

    override fun noStateLoaded() {
        migrateLegacyProperties()
    }

    var defaultViewer: DefaultViewer
        get() = state.defaultViewer
        set(value) { state.defaultViewer = value }

    var ignoreWhitespace: Boolean
        get() = state.ignoreWhitespace
        set(value) { state.ignoreWhitespace = value }

    var collapseUnchanged: Boolean
        get() = state.collapseUnchanged
        set(value) { state.collapseUnchanged = value }

    /** Files above this many lines are not rendered (NFR-4, see [LargeFiles]). */
    var largeFileLines: Int
        get() = state.largeFileLines
        set(value) { state.largeFileLines = value }

    /** Files above this size in KB are not rendered (NFR-4, see [LargeFiles]). */
    var largeFileKilobytes: Int
        get() = state.largeFileKilobytes
        set(value) { state.largeFileKilobytes = value }

    /** Remote images load only when allowed (NFR-8): passed to the renderer and opened in the page's CSP. */
    var loadRemoteImages: Boolean
        get() = state.loadRemoteImages
        set(value) { state.loadRemoteImages = value }

    /** Draw ` ```mermaid ` fences as diagrams (P2.8); off keeps the line-by-line code view. */
    var renderMermaid: Boolean
        get() = state.renderMermaid
        set(value) { state.renderMermaid = value }

    /** Revert and apply buttons on changed blocks of writable sides (FR-12). */
    var showBlockActions: Boolean
        get() = state.showBlockActions
        set(value) { state.showBlockActions = value }

    var appliedDefaultViewer: DefaultViewer?
        get() = state.appliedDefaultViewer
        set(value) { state.appliedDefaultViewer = value }

    fun options() = DiffOptions(
        ignoreWhitespace = ignoreWhitespace,
        collapseUnchanged = collapseUnchanged,
        loadRemoteImages = loadRemoteImages,
        renderMermaid = renderMermaid,
    )

    /**
     * Moves the toggle values that version 0.1 kept in `PropertiesComponent` into this state, then drops the keys.
     * 0.1 stored a key only when its value differed from the default, so a missing key keeps the default.
     */
    internal fun migrateLegacyProperties(props: PropertiesComponent = PropertiesComponent.getInstance()) {
        if (props.isValueSet(LEGACY_IGNORE_WHITESPACE)) {
            ignoreWhitespace = props.getBoolean(LEGACY_IGNORE_WHITESPACE, false)
            props.unsetValue(LEGACY_IGNORE_WHITESPACE)
        }
        if (props.isValueSet(LEGACY_COLLAPSE_UNCHANGED)) {
            collapseUnchanged = props.getBoolean(LEGACY_COLLAPSE_UNCHANGED, true)
            props.unsetValue(LEGACY_COLLAPSE_UNCHANGED)
        }
    }

    companion object {
        const val DEFAULT_LARGE_FILE_LINES = 20_000
        const val DEFAULT_LARGE_FILE_KILOBYTES = 2_048
        internal const val LEGACY_IGNORE_WHITESPACE = "markdown.review.ignoreWhitespace"
        internal const val LEGACY_COLLAPSE_UNCHANGED = "markdown.review.collapseUnchanged"

        fun getInstance(): MarkdownReviewSettings =
            ApplicationManager.getApplication().getService(MarkdownReviewSettings::class.java)
    }
}
