package pl.mdreview.ide

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile

/**
 * Tells whether a file type is Markdown. The only implementation lives in the optional part of the plugin
 * that loads with the bundled Markdown plugin, so files mapped to its file type by name pattern (not by a
 * `.md`-like extension) are recognized too. Rendering never uses that plugin.
 */
interface MarkdownFileTypeRecognizer {
    fun isMarkdown(fileType: FileType): Boolean
}

/** Decides which files the rendered viewers and **Compare Markdown Files...** accept. */
object MarkdownFiles {
    val EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

    val RECOGNIZER_EP = ExtensionPointName<MarkdownFileTypeRecognizer>("pl.mdreview.markdownFileTypeRecognizer")

    fun isMarkdown(file: VirtualFile): Boolean =
        hasMarkdownExtension(file.name) || isMarkdown(file.fileType)

    fun isMarkdown(fileType: FileType): Boolean =
        fileType.defaultExtension.lowercase() in EXTENSIONS || RECOGNIZER_EP.extensionList.any { it.isMarkdown(fileType) }

    fun hasMarkdownExtension(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS
}
