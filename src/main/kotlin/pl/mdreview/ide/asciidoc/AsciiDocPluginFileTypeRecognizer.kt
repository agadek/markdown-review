package pl.mdreview.ide.asciidoc

import com.intellij.openapi.fileTypes.FileType
import pl.mdreview.api.DocumentFormat
import pl.mdreview.ide.DocumentFileTypeRecognizer

/**
 * Registered only when the JetBrains AsciiDoc plugin is enabled (`markdown-review-withAsciiDoc.xml`).
 *
 * Matches its file type by name so this class imports nothing from that plugin and loads no AsciidoctorJ class.
 * A file mapped to the AsciiDoc file type is recognized even when its name has another extension, which is how
 * `.asc` is accepted (ADR 0007); `.asc` itself is not one of [pl.mdreview.ide.ReviewFiles.ASCIIDOC_EXTENSIONS].
 */
class AsciiDocPluginFileTypeRecognizer : DocumentFileTypeRecognizer {
    override fun formatOf(fileType: FileType): DocumentFormat? =
        if (fileType.name == ASCIIDOC_FILE_TYPE_NAME) DocumentFormat.ASCIIDOC else null

    companion object {
        /** The name the AsciiDoc plugin registers for `org.asciidoc.intellij.file.AsciiDocFileType`. */
        const val ASCIIDOC_FILE_TYPE_NAME = "AsciiDoc"
    }
}
