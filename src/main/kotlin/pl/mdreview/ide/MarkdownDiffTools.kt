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
import pl.mdreview.api.DocumentFormat
import pl.mdreview.api.SizeLimit
import pl.mdreview.api.ViewMode
import javax.swing.Icon

/**
 * Rendered viewers registered as `diff.DiffTool`, so they appear in the viewer switcher of every
 * IntelliJ diff window (commit, history, branch compare, PR review) next to the built-in text viewers.
 * Without JCEF they report `canShow = false` and IntelliJ keeps its text viewer (NFR-6).
 *
 * One pair of viewers serves both formats (ADR 0007). Class names are kept because the stored viewer order in
 * ADR 0001 is keyed by them.
 */
abstract class RenderedMarkdownDiffTool(
    private val mode: ViewMode,
    private val nameKey: String,
    private val tooltipKey: String,
    private val icon: Icon,
) : FrameDiffTool {

    override fun getName(): String = MarkdownReviewBundle.message(nameKey)

    /**
     * Since 2026.2 the viewer switcher is a row of icon buttons with the name as tooltip, and `FrameDiffTool.getIcon()`
     * defaults to the built-in side-by-side icon, so without this both rendered viewers look like the text viewer.
     * Not `override`: the method does not exist in the 2025.1 platform the plugin compiles against, and older IDEs show
     * a drop-down of names that never calls it.
     */
    fun getIcon(): Icon = icon

    /** Names both formats, for a tooltip where the platform has one next to [getName]. */
    fun getTooltip(): String = MarkdownReviewBundle.message(tooltipKey)

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean {
        if (!accepts(request)) return false
        // "Open in Text Diff Here" from a block's menu, for one reopen of this window.
        if (context.getUserData(TEXT_VIEWER_ONCE) == true) return false
        // The user chose the text viewer from the large-file notice in this window.
        if (context.getUserData(TEXT_VIEWER_FOR_LARGE_FILES) == true && isTooLarge((request as ContentDiffRequest).contents)) return false
        return JBCefApp.isSupported()
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        RenderedDiffViewer(context, request as ContentDiffRequest, mode)

    companion object {
        /**
         * The request part of [canShow]: two sides, each a document or missing, with one common format that is
         * available. Sides that exist but have no recognizable format are ignored, so a rename from `.md` to `.txt`
         * keeps working; a pair of different known formats, such as `.md` to `.adoc`, is rejected (ADR 0007).
         */
        fun accepts(request: DiffRequest): Boolean {
            if (request !is ContentDiffRequest) return false
            val contents = request.contents
            if (contents.size != 2) return false
            if (contents.any { it !is DocumentContent && it !is EmptyContent }) return false
            val format = formatOf(contents) ?: return false
            return ReviewFiles.isSupported(format)
        }

        /** The one format of the contents that exist, or `null` when none is recognizable or two formats differ. */
        fun formatOf(contents: List<DiffContent>): DocumentFormat? =
            contents.mapNotNull(::formatOf).distinct().singleOrNull()

        fun formatOf(content: DiffContent): DocumentFormat? {
            val document = content as? DocumentContent ?: return null
            document.highlightFile?.let { file -> ReviewFiles.formatOf(file)?.let { return it } }
            return document.contentType?.let(ReviewFiles::formatOf)
        }

        /** Set on the diff context by the large-file notice's button; rendered viewers then step aside. */
        val TEXT_VIEWER_FOR_LARGE_FILES: Key<Boolean> = Key.create("MarkdownReview.textViewerForLargeFiles")

        /** Set on the diff context while a block's menu reopens the window in the text viewer (P3.4). */
        val TEXT_VIEWER_ONCE: Key<Boolean> = Key.create("MarkdownReview.textViewerOnce")

        fun isTooLarge(contents: List<DiffContent>, limit: SizeLimit = LargeFiles.limit()): Boolean =
            contents.any { limit.exceeds((it as? DocumentContent)?.document?.immutableCharSequence) }

        @Deprecated("Use formatOf(content) == DocumentFormat.MARKDOWN")
        fun isMarkdown(content: DiffContent): Boolean = formatOf(content) == DocumentFormat.MARKDOWN
    }
}

class RenderedSideBySideTool :
    RenderedMarkdownDiffTool(ViewMode.SIDE_BY_SIDE, "tool.side.by.side", "tool.side.by.side.tooltip", MarkdownReviewIcons.RENDERED_SIDE_BY_SIDE)

class RenderedMergedTool :
    RenderedMarkdownDiffTool(ViewMode.MERGED, "tool.merged", "tool.merged.tooltip", MarkdownReviewIcons.RENDERED_MERGED)
