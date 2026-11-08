package pl.mdreview.ide

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vfs.VirtualFile
import pl.mdreview.api.DocumentFormat
import java.util.Locale

/**
 * Tells whether a file type is Markdown. The only implementation lives in the optional part of the plugin
 * that loads with the bundled Markdown plugin, so files mapped to its file type by name pattern (not by a
 * `.md`-like extension) are recognized too. Rendering never uses that plugin.
 *
 * Kept for compatibility with `markdown-review-withMarkdown.xml`; new recognizers implement
 * [DocumentFileTypeRecognizer].
 */
interface MarkdownFileTypeRecognizer {
    fun isMarkdown(fileType: FileType): Boolean
}

/**
 * Tells the format of a file type that a format's own plugin owns, for example the AsciiDoc plugin's file type.
 * Registered from the optional descriptor of that plugin, so the platform loads the implementation only while
 * the plugin is installed and enabled.
 */
interface DocumentFileTypeRecognizer {
    fun formatOf(fileType: FileType): DocumentFormat?
}

/**
 * Decides which files the rendered viewers and **Compare Rendered Files...** accept.
 *
 * [formatOf] is a pure classification: it recognizes AsciiDoc also while the optional AsciiDoc plugin is missing.
 * Callers that render must also use [isSupported], which keeps AsciiDoc in the text viewer until [AsciiDocSupport]
 * has a runtime (NFR-11, ADR 0003).
 */
object ReviewFiles {
    val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

    /** `.asc` is deliberately absent: it is also the extension of armored PGP files and is only accepted through the AsciiDoc file type. */
    val ASCIIDOC_EXTENSIONS = setOf("adoc", "asciidoc")

    val MARKDOWN_RECOGNIZER_EP = ExtensionPointName<MarkdownFileTypeRecognizer>("pl.mdreview.markdownFileTypeRecognizer")
    val DOCUMENT_RECOGNIZER_EP = ExtensionPointName<DocumentFileTypeRecognizer>("pl.mdreview.documentFileTypeRecognizer")

    @Deprecated("Use MARKDOWN_EXTENSIONS", ReplaceWith("ReviewFiles.MARKDOWN_EXTENSIONS"))
    val EXTENSIONS: Set<String> = MARKDOWN_EXTENSIONS

    @Deprecated("Use MARKDOWN_RECOGNIZER_EP", ReplaceWith("ReviewFiles.MARKDOWN_RECOGNIZER_EP"))
    val RECOGNIZER_EP: ExtensionPointName<MarkdownFileTypeRecognizer> = MARKDOWN_RECOGNIZER_EP

    fun formatOf(file: VirtualFile): DocumentFormat? =
        formatOfExtension(file.extension) ?: formatOf(file.fileType)

    /** For a path from VCS, which may name a deleted file or a revision that has no [VirtualFile]. */
    fun formatOf(path: FilePath): DocumentFormat? =
        formatOfExtension(path.name.substringAfterLast('.', "")) ?: formatOf(path.fileType)

    fun formatOf(fileType: FileType): DocumentFormat? {
        formatOfExtension(fileType.defaultExtension)?.let { return it }
        if (MARKDOWN_RECOGNIZER_EP.extensionList.any { it.isMarkdown(fileType) }) return DocumentFormat.MARKDOWN
        for (recognizer in DOCUMENT_RECOGNIZER_EP.extensionList) {
            recognizer.formatOf(fileType)?.let { return it }
        }
        return null
    }

    /**
     * Whether rendered support for [format] is active now: the format is turned on in the settings (FR-26), and it is
     * available. Markdown always is available, AsciiDoc only with [AsciiDocSupport].
     */
    fun isSupported(format: DocumentFormat?): Boolean {
        if (format == null || !MarkdownReviewSettings.getInstance().isEnabled(format)) return false
        return isAvailable(format)
    }

    /** Whether [format] can be rendered at all, regardless of the settings; AsciiDoc needs [AsciiDocSupport]. */
    fun isAvailable(format: DocumentFormat): Boolean = when (format) {
        DocumentFormat.MARKDOWN -> true
        DocumentFormat.ASCIIDOC -> AsciiDocSupport.isAvailable()
    }

    fun isSupported(file: VirtualFile): Boolean = isSupported(formatOf(file))

    fun isSupported(path: FilePath): Boolean = isSupported(formatOf(path))

    /**
     * The one format of [paths], ignoring sides that have no recognizable format (a rename to or from Markdown or
     * AsciiDoc). `null` when none is recognizable or when two different formats appear (a `.md` to `.adoc` rename).
     */
    fun commonFormat(paths: Iterable<FilePath>): DocumentFormat? =
        paths.mapNotNull(::formatOf).distinct().singleOrNull()

    @Deprecated("Use formatOf", ReplaceWith("ReviewFiles.formatOf(file) == DocumentFormat.MARKDOWN"))
    fun isMarkdown(file: VirtualFile): Boolean = formatOf(file) == DocumentFormat.MARKDOWN

    @Deprecated("Use formatOf", ReplaceWith("ReviewFiles.formatOf(path) == DocumentFormat.MARKDOWN"))
    fun isMarkdown(path: FilePath): Boolean = formatOf(path) == DocumentFormat.MARKDOWN

    @Deprecated("Use formatOf", ReplaceWith("ReviewFiles.formatOf(fileType) == DocumentFormat.MARKDOWN"))
    fun isMarkdown(fileType: FileType): Boolean = formatOf(fileType) == DocumentFormat.MARKDOWN

    @Deprecated("Use ReviewFiles.formatOf or ReviewFiles.MARKDOWN_EXTENSIONS")
    fun hasMarkdownExtension(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in MARKDOWN_EXTENSIONS

    private fun formatOfExtension(extension: String?): DocumentFormat? = when (extension?.trimStart('.').orEmpty().lowercase(Locale.ROOT)) {
        in MARKDOWN_EXTENSIONS -> DocumentFormat.MARKDOWN
        in ASCIIDOC_EXTENSIONS -> DocumentFormat.ASCIIDOC
        else -> null
    }
}

@Deprecated("Use ReviewFiles", ReplaceWith("ReviewFiles"))
typealias MarkdownFiles = ReviewFiles
