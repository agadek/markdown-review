package pl.mdreview.ide

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty

/** Settings | Tools | Markdown Review (FR-11). */
class MarkdownReviewConfigurable : BoundConfigurable(MarkdownReviewBundle.message("settings.display.name")) {

    private val settings get() = MarkdownReviewSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row(MarkdownReviewBundle.message("settings.default.viewer")) {
            comboBox(DefaultViewer.entries, SimpleListCellRenderer.create("") { MarkdownReviewBundle.message(it.messageKey) })
                .bindItem(settings::defaultViewer.toNullableProperty())
                .comment(MarkdownReviewBundle.message("settings.default.viewer.comment"))
        }
        group(MarkdownReviewBundle.message("settings.group.diff")) {
            row {
                checkBox(MarkdownReviewBundle.message("toggle.ignore.whitespace")).bindSelected(settings::ignoreWhitespace)
            }
            row {
                checkBox(MarkdownReviewBundle.message("toggle.collapse.unchanged")).bindSelected(settings::collapseUnchanged)
            }
            row {
                checkBox(MarkdownReviewBundle.message("settings.load.remote.images")).bindSelected(settings::loadRemoteImages)
                    .comment(MarkdownReviewBundle.message("settings.load.remote.images.comment"))
            }
        }
        group(MarkdownReviewBundle.message("settings.group.large.files")) {
            row(MarkdownReviewBundle.message("settings.large.file.lines")) {
                intTextField(1..Int.MAX_VALUE).bindIntText(settings::largeFileLines)
            }
            row(MarkdownReviewBundle.message("settings.large.file.size")) {
                intTextField(1..Int.MAX_VALUE).bindIntText(settings::largeFileKilobytes)
            }.comment(MarkdownReviewBundle.message("settings.large.file.comment"))
        }
    }

    override fun apply() {
        super.apply()
        DefaultViewerOrder.syncWithSettings(settings)
    }
}
