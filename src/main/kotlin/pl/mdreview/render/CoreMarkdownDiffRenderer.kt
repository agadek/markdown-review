package pl.mdreview.render

import pl.mdreview.api.BlockInfo
import pl.mdreview.api.BlockStatus
import pl.mdreview.api.LineRange
import pl.mdreview.api.MarkdownDiffRenderer
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.RenderedDiff
import pl.mdreview.engine.DiffOptions
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.MarkdownDiffEngine
import pl.mdreview.engine.SourceRange

/**
 * The core's implementation of [MarkdownDiffRenderer], registered in
 * `META-INF/services/pl.mdreview.api.MarkdownDiffRenderer`. Stateless and thread-safe.
 */
class CoreMarkdownDiffRenderer : MarkdownDiffRenderer {

    override fun render(request: RenderRequest): RenderedDiff {
        val engine = MarkdownDiffEngine(
            DiffOptions(
                ignoreWhitespace = request.options.ignoreWhitespace,
                mermaidDiagrams = request.options.renderMermaid,
                pairingThreshold = request.options.pairingThreshold,
            ),
        )
        val left = request.left.orEmpty()
        val right = request.right.orEmpty()
        val diff = engine.diff(left, right)
        val renderer = DiffHtmlRenderer(
            RenderOptions(
                mode = request.mode,
                collapseUnchanged = request.options.collapseUnchanged,
                allowRemoteImages = request.options.loadRemoteImages,
                leftBaseUri = request.leftBaseUri,
                rightBaseUri = request.rightBaseUri,
            ),
        )
        val edits = EditRegions(diff, left, right).compute()
        val blocks = diff.blocks.mapIndexed { i, b ->
            BlockInfo(i, status(b.status), b.left?.source?.let(::lines), b.right?.source?.let(::lines), edits[i])
        }
        return RenderedDiff(renderer.renderBody(diff), renderer.css(), blocks)
    }

    private fun lines(range: SourceRange) = LineRange(range.startLine, range.endLine)

    private fun status(status: DiffStatus) = when (status) {
        DiffStatus.EQUAL -> BlockStatus.EQUAL
        DiffStatus.MODIFIED -> BlockStatus.MODIFIED
        DiffStatus.REMOVED -> BlockStatus.REMOVED
        DiffStatus.ADDED -> BlockStatus.ADDED
    }
}
