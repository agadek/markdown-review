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

/**
 * The core's implementation of [MarkdownDiffRenderer], registered in
 * `META-INF/services/pl.mdreview.api.MarkdownDiffRenderer`. Stateless and thread-safe.
 *
 * [asciidocSource] is optional: when the IDE supplies the AsciiDoc front end of the installed AsciiDoc plugin,
 * `ASCIIDOC` requests use it; without it they keep the raw-text fallback (NFR-11).
 *
 * This class must not name `AsciiDocBlockSource`, `AsciidoctorProvider` or any `org.asciidoctor` class. It is
 * reachable from the main plugin descriptor, and the Plugin Verifier reports every AsciidoctorJ class reachable from
 * there as missing when the optional AsciiDoc plugin cannot be resolved for an IDE build. The front end is created
 * behind `markdown-review-withAsciiDoc.xml` and handed in as a plain [BlockSource].
 */
class CoreMarkdownDiffRenderer(private val asciidocSource: BlockSource? = null) : MarkdownDiffRenderer {

    override fun render(request: RenderRequest): RenderedDiff {
        // A format without a front end still gets a usable diff: raw text, compared paragraph by paragraph.
        val source = if (request.format == DocumentFormat.ASCIIDOC) {
            asciidocSource ?: installedAsciidocSource ?: PlainTextBlockSource
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
        val rules = if (request.format == DocumentFormat.ASCIIDOC) AsciiDocRegionRules else MarkdownRegionRules
        val edits = EditRegions(diff, left, right, rules).compute()
        val blocks = diff.blocks.mapIndexed { i, b ->
            BlockInfo(i, status(b.status), b.left?.source?.let(::lines), b.right?.source?.let(::lines), edits[i], b.movedFrom, b.movedTo)
        }
        return RenderedDiff(renderer.renderBody(diff), renderer.css(), blocks, request.format)
    }

    private fun lines(range: SourceRange) = LineRange(range.startLine, range.endLine)

    private fun status(status: DiffStatus) = when (status) {
        DiffStatus.EQUAL -> BlockStatus.EQUAL
        DiffStatus.MODIFIED -> BlockStatus.MODIFIED
        DiffStatus.REMOVED -> BlockStatus.REMOVED
        DiffStatus.ADDED -> BlockStatus.ADDED
    }

    companion object {
        /** The front end installed by the optional AsciiDoc integration, used by the ServiceLoader instance. */
        @Volatile
        private var installedAsciidocSource: BlockSource? = null

        /**
         * Installs the AsciiDoc front end for renderers created through [java.util.ServiceLoader]. Called by the
         * IDE layer when the optional AsciiDoc plugin is enabled; [uninstallAsciiDocSource] when it unloads.
         */
        fun installAsciiDocSource(source: BlockSource?) {
            installedAsciidocSource = source
        }

        fun uninstallAsciiDocSource() {
            installedAsciidocSource = null
        }

        /** Front ends by format. P4.3 adds [DocumentFormat.ASCIIDOC] here. */
        private val SOURCES: Map<DocumentFormat, BlockSource> = mapOf(DocumentFormat.MARKDOWN to MarkdownBlockSource)

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
