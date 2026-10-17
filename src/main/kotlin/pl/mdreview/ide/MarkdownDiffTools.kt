package pl.mdreview.ide

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.ui.jcef.JBCefApp
import pl.mdreview.api.ViewMode

/**
 * Rendered viewers registered as `diff.DiffTool`, so they appear in the viewer switcher of every
 * IntelliJ diff window (commit, history, branch compare, PR review) next to the built-in text viewers.
 * Without JCEF they report `canShow = false` and IntelliJ keeps its text viewer (NFR-6).
 */
abstract class RenderedMarkdownDiffTool(private val mode: ViewMode, private val nameKey: String) : FrameDiffTool {

    override fun getName(): String = MarkdownReviewBundle.message(nameKey)

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean {
        if (request !is ContentDiffRequest) return false
        val contents = request.contents
        if (contents.size != 2) return false
        if (contents.any { it !is DocumentContent && it !is EmptyContent }) return false
        if (contents.none { it is DocumentContent && isMarkdown(it) }) return false
        return JBCefApp.isSupported()
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        RenderedDiffViewer(request as ContentDiffRequest, mode)

    companion object {
        val EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

        fun isMarkdown(content: DiffContent): Boolean {
            val file = (content as? DocumentContent)?.highlightFile
            if (file != null && file.extension?.lowercase() in EXTENSIONS) return true
            val type = content.contentType ?: return false
            return type.name.equals("Markdown", ignoreCase = true) || type.defaultExtension.lowercase() in EXTENSIONS
        }
    }
}

class RenderedSideBySideTool : RenderedMarkdownDiffTool(ViewMode.SIDE_BY_SIDE, "tool.side.by.side")

class RenderedMergedTool : RenderedMarkdownDiffTool(ViewMode.MERGED, "tool.merged")
