package pl.mdreview.ide

import com.intellij.ide.util.PropertiesComponent
import pl.mdreview.api.DiffOptions

/** Toolbar toggles remembered across diff windows. A full settings page (FR-11) comes later. */
object MarkdownReviewSettings {
    private const val IGNORE_WHITESPACE = "markdown.review.ignoreWhitespace"
    private const val COLLAPSE_UNCHANGED = "markdown.review.collapseUnchanged"

    private val props get() = PropertiesComponent.getInstance()

    var ignoreWhitespace: Boolean
        get() = props.getBoolean(IGNORE_WHITESPACE, false)
        set(value) = props.setValue(IGNORE_WHITESPACE, value, false)

    var collapseUnchanged: Boolean
        get() = props.getBoolean(COLLAPSE_UNCHANGED, true)
        set(value) = props.setValue(COLLAPSE_UNCHANGED, value, true)

    fun options() = DiffOptions(ignoreWhitespace = ignoreWhitespace, collapseUnchanged = collapseUnchanged)
}
