package pl.mdreview.engine

/**
 * A format front end: parses one document format and flattens it into the blocks the diff engine compares.
 *
 * [MarkdownBlockSource] implements this for Markdown. An AsciiDoc front end will implement it without changing
 * the engine or the HTML generator.
 */
interface BlockSource {
    fun extract(text: String, options: DiffOptions): List<Block>
}
