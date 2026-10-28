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
}

enum class DiffStatus { EQUAL, MODIFIED, REMOVED, ADDED }

/** Zero-based, end-exclusive line and offset range of a block in its source text. */
data class SourceRange(val startLine: Int, val endLine: Int, val startOffset: Int, val endOffset: Int)

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
)

/**
 * One comparable unit of a Markdown document.
 *
 * [key] is what the matcher compares; [plainText] is the visible text the word diff runs on.
 * [node] is the commonmark node to render (null for code lines, front matter and code fences,
 * which are rendered from [rawText]).
 */
class Block(
    val kind: BlockKind,
    val key: String,
    val plainText: String,
    val rawText: String,
    val source: SourceRange,
    val context: BlockContext = BlockContext(),
    val node: Node? = null,
    /** For a list item: the children to render (everything except nested lists). */
    val renderNodes: List<Node> = listOfNotNull(node),
    val language: String? = null,
    /** Heading level (1–6), 0 for other blocks. */
    val level: Int = 0,
    /**
     * For a [BlockKind.MERMAID] block: the blocks the fence would be split into without diagram rendering. For a
     * [BlockKind.MATH] block: its source lines.
     */
    val lines: List<Block> = emptyList(),
) {
    /** Ranges of [plainText] that the word diff keeps whole: inline formulas. */
    val atoms: List<IntRange> by lazy { BlockExtractor.atoms(this) }

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
