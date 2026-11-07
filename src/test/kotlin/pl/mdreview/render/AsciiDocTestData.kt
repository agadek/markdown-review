package pl.mdreview.render

import pl.mdreview.api.ViewMode
import pl.mdreview.engine.Block
import pl.mdreview.engine.BlockContent
import pl.mdreview.engine.BlockContext
import pl.mdreview.engine.BlockKind
import pl.mdreview.engine.Container
import pl.mdreview.engine.DiffEngine
import pl.mdreview.engine.HtmlFragment
import pl.mdreview.engine.SourceRange

/**
 * Manually built AsciiDoc blocks for the renderer tests. P4.3's front end is not on this branch yet, so the tests
 * supply the exact model it will produce: sanitized inline HTML, contexts with containers, and the new block kinds.
 */
internal object AsciiDocTestData {

    fun block(
        kind: BlockKind,
        plain: String,
        html: String? = null,
        raw: String = plain,
        context: BlockContext = BlockContext(),
        level: Int = 0,
        language: String? = null,
        target: String? = null,
        title: String? = null,
        label: String? = null,
        line: Int = 0,
    ): Block = Block(
        kind = kind,
        key = key(kind, raw, html.orEmpty(), plain, context),
        plainText = plain,
        rawText = raw,
        source = SourceRange(line, line + 1, 0, raw.length),
        context = context,
        content = if (html == null) BlockContent.Raw else BlockContent.Html(HtmlFragment.parse(html, allowRemoteImages = true)),
        language = language,
        level = level,
        label = label,
        target = target,
        title = title,
    )

    private fun key(kind: BlockKind, raw: String, html: String, plain: String, context: BlockContext): String = buildString {
        append(kind.name).append(':')
        context.containers.forEach { append(it.kind).append('/').append(it.type.orEmpty()).append(';') }
        append(context.listDepth).append(':').append(context.ordered).append(':').append(context.number).append(':')
        append(raw).append('\u0000').append(html).append('\u0000').append(plain)
    }

    fun diff(left: List<Block>, right: List<Block>) = DiffEngine().diff(left, right)

    fun render(
        left: List<Block>,
        right: List<Block>,
        mode: ViewMode = ViewMode.SIDE_BY_SIDE,
        options: RenderOptions = RenderOptions(mode = mode, collapseUnchanged = false),
    ): String = DiffHtmlRenderer(options.copy(mode = mode, collapseUnchanged = false)).renderBody(diff(left, right))

    fun paragraph(
        plain: String,
        html: String = plain,
        containers: List<Container> = emptyList(),
        raw: String = plain,
        line: Int = 0,
    ) = block(BlockKind.PARAGRAPH, plain, html, raw, BlockContext(containers = containers), line = line)

    fun heading(
        plain: String,
        level: Int = 1,
        html: String = plain,
        raw: String = "# $plain",
        containers: List<Container> = emptyList(),
        line: Int = 0,
    ) = block(BlockKind.HEADING, plain, html, raw, BlockContext(containers = containers), level = level, line = line)

    fun listItem(
        plain: String,
        html: String = plain,
        raw: String = "* $plain",
        depth: Int = 1,
        ordered: Boolean = false,
        number: Int? = null,
        containers: List<Container> = emptyList(),
        line: Int = 0,
    ) = block(
        BlockKind.LIST_ITEM,
        plain,
        html,
        raw,
        BlockContext(listDepth = depth, ordered = ordered, number = number, containers = containers),
        line = line,
    )

    fun descriptionTerm(plain: String, html: String = plain, containers: List<Container> = listOf(Container("description")), line: Int = 0) =
        block(BlockKind.DESCRIPTION_TERM, plain, html, "$plain::", BlockContext(containers = containers), line = line)

    fun descriptionItem(plain: String, html: String = plain, containers: List<Container> = listOf(Container("description")), line: Int = 0) =
        block(BlockKind.LIST_ITEM, plain, html, "  $plain", BlockContext(listDepth = 1, containers = containers), line = line)

    fun tableRow(html: String, plain: String, raw: String, header: Boolean = false, line: Int = 0) =
        block(BlockKind.TABLE_ROW, plain, html, raw, line = line).let {
            Block(
                it.kind,
                (if (header) "th:" else "td:") + it.key,
                it.plainText,
                it.rawText,
                it.source,
                it.context,
                it.content,
            )
        }

    fun codeFence(style: String?, language: String?, raw: String, line: Int = 0) =
        block(BlockKind.CODE_FENCE, style.orEmpty(), null, raw, language = language, line = line)

    fun codeLine(text: String, language: String?, line: Int = 0) =
        block(BlockKind.CODE_LINE, text, null, text, language = language, line = line)

    fun alert(
        plain: String,
        html: String = plain,
        type: String = "NOTE",
        container: Boolean = true,
        alertContext: String? = null,
        raw: String = "NOTE: $plain",
        line: Int = 0,
    ): Block {
        val context = BlockContext(
            alert = alertContext,
            containers = if (container) listOf(Container("admonition", type = type)) else emptyList(),
        )
        return block(BlockKind.ALERT, plain, html, raw, context, line = line)
    }

    fun blockTitle(plain: String, html: String = plain, raw: String = ".$plain", line: Int = 0) =
        block(BlockKind.BLOCK_TITLE, plain, html, raw, line = line)

    fun image(alt: String, target: String, title: String? = null, line: Int = 0) =
        block(BlockKind.IMAGE, alt, null, "image::$target[$alt]", target = target, title = title, line = line)

    fun attribute(entry: String, line: Int = 0) = block(BlockKind.ATTRIBUTE, entry, null, entry, line = line)

    fun include(target: String, line: Int = 0) =
        block(BlockKind.INCLUDE, "include::$target[]", null, "include::$target[]", target = target, line = line)

    fun comment(text: String, line: Int = 0) =
        block(BlockKind.HTML_COMMENT, text, "<!-- $text -->", "<!-- $text -->", line = line)

    fun thematicBreak(raw: String = "'''", line: Int = 0) = block(BlockKind.THEMATIC_BREAK, "", null, raw, line = line)

    fun html(html: String, line: Int = 0) = block(BlockKind.HTML, HtmlFragment.visibleText(html), html, html, line = line)

    /**
     * Fixture `56-adoc-containers`: example, sidebar, quote and open blocks, a nested sidebar, and the two breaks.
     * The changed paragraphs are inside their containers.
     */
    fun containersFixture(): Pair<List<Block>, List<Block>> {
        fun side(revised: Boolean): List<Block> {
            val example = Container("example", title = "Example block")
            val sidebar = Container("sidebar", title = "Sidebar")
            val quote = Container("quote")
            val open = Container("open")
            val outer = Container("example", title = "Example with a sidebar")
            val inner = Container("sidebar")
            fun exampleText() = if (revised) "Example content, revised." else "Example content."
            fun sidebarText() = if (revised) "Sidebar content with extra detail." else "Sidebar content."
            fun openText() = if (revised) {
                "Open block content with an inline <strong>bold</strong> phrase."
            } else {
                "Open block content."
            }
            fun openPlain() = if (revised) "Open block content with an inline bold phrase." else "Open block content."
            fun innerText() = if (revised) "Inner sidebar content, revised." else "Inner sidebar content."
            return listOf(
                heading("Containers", raw = "= Containers", line = 0),
                paragraph("Intro paragraph before the containers.", line = 2),
                paragraph(exampleText(), containers = listOf(example), line = 4),
                paragraph(sidebarText(), containers = listOf(sidebar), line = 8),
                paragraph(
                    "The Analytical Engine has no pretensions " +
                        (if (revised) "" else "whatever ") + "to originate anything.",
                    containers = listOf(quote),
                    line = 12,
                ),
                paragraph(openPlain(), openText(), containers = listOf(open), line = 16),
                paragraph("Outer content.", containers = listOf(outer), line = 21),
                paragraph(innerText(), containers = listOf(outer, inner), line = 23),
                paragraph("Closing paragraph after the containers.", line = 28),
                thematicBreak(line = 30),
                thematicBreak("<<<", line = 32),
                html("<p>Raw <strong>HTML</strong> from a passthrough block.</p>", line = 34),
            )
        }
        return side(false) to side(true)
    }

    /**
     * Fixture `61-adoc-links-images`: link text and target changes, xref and `<<id>>` targets, and inline and block
     * images.
     */
    fun linksFixture(): Pair<List<Block>, List<Block>> {
        fun side(revised: Boolean): List<Block> {
            val docsText = if (revised) "user documentation" else "documentation"
            val download = if (revised) "https://example.com/releases" else "https://example.com/download"
            val xref = if (revised) "setup.adoc" else "installation.adoc"
            val anchor = if (revised) "configuration" else "settings"
            val icon = if (revised) "images/icon-dark.png" else "images/icon.png"
            val screenshot = if (revised) "images/screenshot-dark.png" else "images/screenshot.png"
            return listOf(
                heading("Links and Images", raw = "= Links and Images", line = 0),
                paragraph(
                    "See the $docsText for details.",
                    "See the <a href=\"https://example.com/docs\">$docsText</a> for details.",
                    line = 2,
                ),
                paragraph(
                    "Download from the download page.",
                    "Download from the <a href=\"$download\">download page</a>.",
                    line = 4,
                ),
                paragraph(
                    "Read the installation guide first.",
                    "Read the <a href=\"$xref\">installation guide</a> first.",
                    line = 6,
                ),
                paragraph(
                    "The settings are described in Settings.",
                    "The settings are described in <a href=\"#$anchor\">Settings</a>.",
                    line = 8,
                ),
                paragraph(
                    "An inline icon: .",
                    "An inline icon: <img src=\"$icon\" alt=\"Icon\" width=\"24\" height=\"24\">.",
                    line = 10,
                ),
                image("Screenshot of the diff window", screenshot, line = 12),
                heading("Settings", level = 2, raw = "== Settings", line = 15),
                paragraph("Settings text.", line = 17),
                paragraph("A plain paragraph without links.", line = 19),
            )
        }
        return side(false) to side(true)
    }
}
