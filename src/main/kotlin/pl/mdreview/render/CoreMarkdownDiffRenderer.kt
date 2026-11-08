package pl.mdreview.render

import pl.mdreview.api.BlockInfo
import pl.mdreview.api.BlockStatus
import pl.mdreview.api.DocumentFormat
import pl.mdreview.api.LineRange
import pl.mdreview.api.MarkdownDiffRenderer
import pl.mdreview.api.RenderRequest
import pl.mdreview.api.RenderedDiff
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.BlockSource
import pl.mdreview.engine.DiffEngine
import pl.mdreview.engine.DiffOptions
import pl.mdreview.engine.DiffStatus
import pl.mdreview.engine.MarkdownBlockSource
import pl.mdreview.engine.SourceRange
import pl.mdreview.engine.asciidoc.AsciiDocBlockSource
import pl.mdreview.engine.asciidoc.AsciidoctorProvider

/**
 * The core's implementation of [MarkdownDiffRenderer], registered in
 * `META-INF/services/pl.mdreview.api.MarkdownDiffRenderer`. Stateless and thread-safe.
 *
 * [asciidoctor] is optional: when the IDE supplies the provider of the installed AsciiDoc plugin, `ASCIIDOC`
 * requests get the AsciiDoc front end; without it they keep the raw-text fallback (NFR-11).
 */
class CoreMarkdownDiffRenderer(private val asciidoctor: AsciidoctorProvider? = null) : MarkdownDiffRenderer {

    /** Created once; the provider returns the session's single thread-safe [org.asciidoctor.Asciidoctor]. */
    private val asciidocSource: BlockSource? = asciidoctor?.let { AsciiDocBlockSource(it) }

    override fun render(request: RenderRequest): RenderedDiff {
        // A format without a front end still gets a usable diff: raw text, compared paragraph by paragraph.
        val source = if (request.format == DocumentFormat.ASCIIDOC && asciidocSource != null) {
            asciidocSource
        } else {
            SOURCES[request.format] ?: PlainTextBlockSource
        }
        return render(request, source)
    }

    private fun render(request: RenderRequest, source: BlockSource): RenderedDiff {
        val engine = DiffEngine(
            DiffOptions(
                ignoreWhitespace = request.options.ignoreWhitespace,
                mermaidDiagrams = request.options.renderMermaid,
                pairingThreshold = request.options.pairingThreshold,
            ),
            source,
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
            BlockInfo(i, status(b.status), b.left?.source?.let(::lines), b.right?.source?.let(::lines), edits[i], b.movedFrom, b.movedTo)
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

    private companion object {
        /** Front ends by format. P4.3 adds [DocumentFormat.ASCIIDOC] here. */
        val SOURCES: Map<DocumentFormat, BlockSource> = mapOf(DocumentFormat.MARKDOWN to MarkdownBlockSource)

        /**
         * Raw text, split into blank-line separated paragraphs. Used for a format that has no front end yet; the
         * page gets the same block structure and source lines, without any format-specific rendering.
         */
        object PlainTextBlockSource : BlockSource {
            override fun extract(text: String, options: DiffOptions): List<Block> {
                val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
                val lines = normalized.split('\n')
                val starts = IntArray(lines.size + 1)
                var offset = 0
                for (i in lines.indices) {
                    starts[i] = offset
                    offset += lines[i].length + 1
                }
                starts[lines.size] = offset

                val blocks = mutableListOf<Block>()
                var start = -1
                fun emit(end: Int) {
                    if (start < 0) return
                    val chunk = lines.subList(start, end).joinToString("\n")
                    blocks += Block(
                        kind = BlockKind.PARAGRAPH,
                        key = chunk,
                        plainText = chunk,
                        rawText = chunk,
                        source = SourceRange(start, end, starts[start], minOf(starts[end], normalized.length)),
                        content = BlockContent.Raw,
                    )
                    start = -1
                }
                for (i in 0..lines.size) {
                    val blank = i == lines.size || lines[i].isBlank()
                    if (!blank && start < 0) start = i
                    if (blank) emit(i)
                }
                return blocks
            }
        }
    }
}
