package pl.mdreview.engine

import org.commonmark.node.Node
import pl.mdreview.api.DiffOptions.Companion.DEFAULT_PAIRING_THRESHOLD

enum class BlockKind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TABLE_ROW,
    CODE_FENCE,
    CODE_LINE,
    THEMATIC_BREAK,
    HTML,
    FRONT_MATTER,
    /** A footnote definition (`[^label]: text`). */
    FOOTNOTE,
    /** The title line of a GitHub alert (`> [!NOTE]`); the alert's content follows as quoted blocks. */
    ALERT,
    /**
     * A ` ```mermaid ` fence kept whole: compared as one unit and drawn as a diagram. [Block.lines] holds
     * the fence and its source lines as [CODE_FENCE] and [CODE_LINE] blocks, for the source diff.
     */
    MERMAID,
    /**
     * The opening of a `<details>` element whose content spans several Markdown blocks: its summary. The content
     * follows as blocks with a deeper [BlockContext.details]; the closing `</details>` is not a block.
     */
    DETAILS,
    /**
     * A block formula (`$$ ... $$`), compared as one unit; [Block.plainText] is its TeX source. [Block.lines] holds
     * its source lines as [CODE_LINE] blocks, for the source diff.
     */
    MATH,
    /** An HTML block that is exactly one comment (`<!-- ... -->`); [Block.plainText] is the comment's text. */
    HTML_COMMENT,

    /** An AsciiDoc attribute entry (`:name: value`), compared as written; [Block.plainText] is the entry. */
    ATTRIBUTE,

    /** An AsciiDoc block title (`.Title`) before the block it titles; [Block.plainText] is the title. */
    BLOCK_TITLE,

    /** The term of an AsciiDoc description list entry (`term:: description`). */
    DESCRIPTION_TERM,

    /** An AsciiDoc block image (`image::target[alt]`); [Block.target] is the target, [Block.plainText] the alt text. */
    IMAGE,

    /** An AsciiDoc `include::target[]` directive; [Block.target] is the target, never resolved. */
    INCLUDE,
}

enum class DiffStatus { EQUAL, MODIFIED, REMOVED, ADDED }

/** Zero-based, end-exclusive line and offset range of a block in its source text. */
data class SourceRange(val startLine: Int, val endLine: Int, val startOffset: Int, val endOffset: Int)

/** A delimited container a block sits in, independent of the document format. */
data class Container(val kind: String, val type: String? = null, val title: String? = null)

/** Where a block sits: inside how many quotes, and for list items the list it belongs to. */
data class BlockContext(
    val quoteDepth: Int = 0,
    val listDepth: Int = 0,
    val ordered: Boolean = false,
    val number: Int? = null,
    /** Type of the GitHub alert (`NOTE`, `TIP`, ...) the block sits in; the alert is the outermost quote. */
    val alert: String? = null,
    /** How many multi-block `<details>` elements the block sits in. */
    val details: Int = 0,
    /** Delimited containers the block sits in, outermost first. Empty for Markdown. */
    val containers: List<Container> = emptyList(),
)

/** How a block's visible content is rendered. */
sealed interface BlockContent {
    /** commonmark nodes, rendered as Markdown. */
    data class Commonmark(val nodes: List<Node>) : BlockContent

    /** Sanitized HTML parts, rendered with word marks on their text runs. */
    data class Html(val parts: List<HtmlFragment.Part>) : BlockContent

    /** No structured content; the block is rendered from its source. */
    data object Raw : BlockContent
}

/**
 * One comparable unit of a document.
 *
 * [key] is what the matcher compares; [plainText] is the visible text the word diff runs on.
 * [content] says how the block is rendered: commonmark nodes for Markdown blocks, sanitized HTML for HTML blocks,
 * [BlockContent.Raw] for code lines, front matter and other blocks rendered from [rawText].
 */
class Block(
    val kind: BlockKind,
    val key: String,
    val plainText: String,
    val rawText: String,
    val source: SourceRange,
    val context: BlockContext = BlockContext(),
    val content: BlockContent = BlockContent.Raw,
    val language: String? = null,
    /** Heading level (1–6), 0 for other blocks. */
    val level: Int = 0,
    /**
     * For a [BlockKind.MERMAID] block: the blocks the fence would be split into without diagram rendering. For a
     * [BlockKind.MATH] block: its source lines.
     */
    val lines: List<Block> = emptyList(),
    /** For a [BlockKind.FOOTNOTE]: its label, without the `[^` and `]`. */
    val label: String? = null,
    /** For an [BlockKind.IMAGE] or [BlockKind.INCLUDE]: its target, as written; never resolved for includes. */
    val target: String? = null,
    /** Optional title, for example a block image's caption (`.Title` is a [BlockKind.BLOCK_TITLE] of its own). */
    val title: String? = null,
) {
    /** The commonmark nodes to render, or an empty list when the content is not [BlockContent.Commonmark]. */
    val nodes: List<Node>
        get() = (content as? BlockContent.Commonmark)?.nodes.orEmpty()

    /** Ranges of [plainText] that the word diff keeps whole: inline formulas. */
    val atoms: List<IntRange> by lazy { MarkdownBlockSource.atoms(this) }

    override fun toString(): String = "$kind(${key.take(40)})"
}

data class InlineSegment(val text: String, val status: DiffStatus)

class DiffBlock(
    val status: DiffStatus,
    val left: Block?,
    val right: Block?,
    /** Word-level diff of [Block.plainText]; filled only for [DiffStatus.MODIFIED]. */
    val inline: List<InlineSegment> = emptyList(),
    /** For an [DiffStatus.ADDED] block that was moved: the index of its [DiffStatus.REMOVED] other end in [MarkdownDiff.blocks]. */
    val movedFrom: Int? = null,
    /** For a [DiffStatus.REMOVED] block that was moved: the index of its [DiffStatus.ADDED] other end. */
    val movedTo: Int? = null,
) {
    val isChange: Boolean get() = status != DiffStatus.EQUAL

    override fun toString(): String = "$status(${left ?: ""} -> ${right ?: ""})"
}

data class DiffOptions(
    /** Ignore all whitespace differences when deciding whether two blocks are equal. */
    val ignoreWhitespace: Boolean = false,
    /** Minimum word similarity (0..1) for a removed and an added block to count as one modified block. */
    val pairingThreshold: Double = DEFAULT_PAIRING_THRESHOLD,
    /** Keep each ` ```mermaid ` fence as one [BlockKind.MERMAID] block instead of one block per line. */
    val mermaidDiagrams: Boolean = true,
)

class MarkdownDiff(val blocks: List<DiffBlock>) {
    val changes: List<DiffBlock> get() = blocks.filter { it.isChange }
    val hasChanges: Boolean get() = blocks.any { it.isChange }
}
