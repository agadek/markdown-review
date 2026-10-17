package pl.mdreview.ide

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import pl.mdreview.api.DiffOptions

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
            row(MarkdownReviewBundle.message("settings.pairing.threshold")) {
                spinner(DiffOptions.MIN_PAIRING_THRESHOLD..DiffOptions.MAX_PAIRING_THRESHOLD, 0.05)
                    .bindValue(settings::pairingThreshold)
                    .comment(MarkdownReviewBundle.message("settings.pairing.threshold.comment", DiffOptions.DEFAULT_PAIRING_THRESHOLD))
            }
            row {
                checkBox(MarkdownReviewBundle.message("settings.load.remote.images")).bindSelected(settings::loadRemoteImages)
                    .comment(MarkdownReviewBundle.message("settings.load.remote.images.comment"))
            }
            row {
                checkBox(MarkdownReviewBundle.message("settings.render.mermaid")).bindSelected(settings::renderMermaid)
                    .comment(MarkdownReviewBundle.message("settings.render.mermaid.comment"))
            }
            row {
                checkBox(MarkdownReviewBundle.message("settings.show.block.actions")).bindSelected(settings::showBlockActions)
                    .comment(MarkdownReviewBundle.message("settings.show.block.actions.comment"))
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
