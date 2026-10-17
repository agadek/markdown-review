package pl.mdreview.ide

import com.intellij.diff.DiffContext
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.util.Key
import com.intellij.ui.jcef.JBCefApp
import pl.mdreview.api.SizeLimit
import pl.mdreview.api.ViewMode

/**
 * Rendered viewers registered as `diff.DiffTool`, so they appear in the viewer switcher of every
 * IntelliJ diff window (commit, history, branch compare, PR review) next to the built-in text viewers.
 * Without JCEF they report `canShow = false` and IntelliJ keeps its text viewer (NFR-6).
 */
abstract class RenderedMarkdownDiffTool(private val mode: ViewMode, private val nameKey: String) : FrameDiffTool {

    override fun getName(): String = MarkdownReviewBundle.message(nameKey)

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean {
        if (!accepts(request)) return false
        // The user chose the text viewer from the large-file notice in this window.
        if (context.getUserData(TEXT_VIEWER_FOR_LARGE_FILES) == true && isTooLarge((request as ContentDiffRequest).contents)) return false
        return JBCefApp.isSupported()
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        RenderedDiffViewer(context, request as ContentDiffRequest, mode)

    companion object {
        /** The request part of [canShow]: two sides, each a document or missing, at least one of them Markdown. */
        fun accepts(request: DiffRequest): Boolean {
            if (request !is ContentDiffRequest) return false
            val contents = request.contents
            if (contents.size != 2) return false
            if (contents.any { it !is DocumentContent && it !is EmptyContent }) return false
            return contents.any { it is DocumentContent && isMarkdown(it) }
        }

        /** Set on the diff context by the large-file notice's button; rendered viewers then step aside. */
        val TEXT_VIEWER_FOR_LARGE_FILES: Key<Boolean> = Key.create("MarkdownReview.textViewerForLargeFiles")

        fun isTooLarge(contents: List<DiffContent>, limit: SizeLimit = LargeFiles.limit()): Boolean =
            contents.any { limit.exceeds((it as? DocumentContent)?.document?.immutableCharSequence) }

        fun isMarkdown(content: DiffContent): Boolean {
            val file = (content as? DocumentContent)?.highlightFile
            if (file != null && MarkdownFiles.isMarkdown(file)) return true
            val type = content.contentType ?: return false
            return MarkdownFiles.isMarkdown(type)
        }
    }
}

class RenderedSideBySideTool : RenderedMarkdownDiffTool(ViewMode.SIDE_BY_SIDE, "tool.side.by.side")

class RenderedMergedTool : RenderedMarkdownDiffTool(ViewMode.MERGED, "tool.merged")
