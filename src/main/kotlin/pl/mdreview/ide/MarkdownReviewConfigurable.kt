package pl.mdreview.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindValue
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import pl.mdreview.api.DiffOptions
import java.util.Hashtable
import java.util.Locale
import javax.swing.JLabel

/** Settings | Tools | Markdown Review (FR-11). */
class MarkdownReviewConfigurable : BoundConfigurable(MarkdownReviewBundle.message("settings.display.name")) {

    private val settings get() = MarkdownReviewSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row(MarkdownReviewBundle.message("settings.default.viewer")) {
            comboBox(DefaultViewer.entries, textListCellRenderer<DefaultViewer?> { it?.let { viewer -> MarkdownReviewBundle.message(viewer.messageKey) } })
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
                slider(percent(DiffOptions.MIN_PAIRING_THRESHOLD), percent(DiffOptions.MAX_PAIRING_THRESHOLD), 5, 10)
                    .applyToComponent {
                        labelTable = Hashtable<Int, JLabel>().apply {
                            for (value in percent(DiffOptions.MIN_PAIRING_THRESHOLD)..percent(DiffOptions.MAX_PAIRING_THRESHOLD) step 10) {
                                put(value, JLabel("%.1f".format(Locale.ROOT, value / 100.0)))
                            }
                        }
                        paintLabels = true
                    }
                    .bindValue({ percent(settings.pairingThreshold) }, { settings.pairingThreshold = it / 100.0 })
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
                checkBox(MarkdownReviewBundle.message("settings.render.math")).bindSelected(settings::renderMath)
                    .comment(MarkdownReviewBundle.message("settings.render.math.comment"))
            }
            row(MarkdownReviewBundle.message("settings.custom.css")) {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withExtensionFilter("css")
                        .withTitle(MarkdownReviewBundle.message("settings.custom.css.choose")),
                )
                    .bindText(settings::customCssPath)
                    .align(AlignX.FILL)
                    .comment(MarkdownReviewBundle.message("settings.custom.css.comment"))
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
        group(MarkdownReviewBundle.message("settings.group.support")) {
            row {
                icon(MarkdownReviewIcons.SPONSORS).gap(RightGap.SMALL)
                browserLink(MarkdownReviewBundle.message("settings.support.sponsors"), MarkdownReviewIcons.SPONSORS_URL)
                icon(MarkdownReviewIcons.KO_FI).gap(RightGap.SMALL)
                browserLink(MarkdownReviewBundle.message("settings.support.kofi"), MarkdownReviewIcons.KO_FI_URL)
            }.rowComment(MarkdownReviewBundle.message("settings.support.comment"))
        }
    }

    /** The slider works in hundredths of the threshold. */
    private fun percent(threshold: Double) = Math.round(threshold * 100).toInt()

    override fun apply() {
        super.apply()
        DefaultViewerOrder.syncWithSettings(settings)
        ApplicationManager.getApplication().messageBus.syncPublisher(MarkdownReviewSettings.TOPIC).settingsChanged()
    }
}
