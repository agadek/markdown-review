package pl.mdreview.ide.markdown

import com.intellij.openapi.fileTypes.FileType
import org.intellij.plugins.markdown.lang.MarkdownFileType
import pl.mdreview.ide.MarkdownFileTypeRecognizer

/** Registered only when the bundled Markdown plugin is enabled (`markdown-review-withMarkdown.xml`). */
class MarkdownPluginFileTypeRecognizer : MarkdownFileTypeRecognizer {
    override fun isMarkdown(fileType: FileType): Boolean = fileType is MarkdownFileType
}
