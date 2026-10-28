package pl.mdreview.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownDiffEngineTest {

    private val engine = MarkdownDiffEngine()

    private fun statuses(left: String, right: String, options: DiffOptions = DiffOptions()) =
        MarkdownDiffEngine(options).diff(left.trimIndent(), right.trimIndent()).blocks.map { it.status }

    @Test
    fun `identical documents have no changes`() {
        val md = "# Title\n\nSome text.\n\n- a\n- b\n"
        val diff = engine.diff(md, md)
        assertFalse(diff.hasChanges)
        assertEquals(4, diff.blocks.size)
    }

    @Test
    fun `edited paragraph is modified with a word diff`() {
        val diff = engine.diff("The quick brown fox.", "The quick red fox.")
        val block = diff.blocks.single()
        assertEquals(DiffStatus.MODIFIED, block.status)
        assertEquals(
            listOf(
                InlineSegment("The quick ", DiffStatus.EQUAL),
                InlineSegment("brown", DiffStatus.REMOVED),
                InlineSegment("red", DiffStatus.ADDED),
                InlineSegment(" fox.", DiffStatus.EQUAL),
            ),
            block.inline,
        )
    }

    @Test
    fun `rewritten paragraph is a removal and an addition`() {
        val result = statuses(
            """
            # Intro

            Completely different words here.
            """,
            """
            # Intro

            Nothing in common with before.
            """,
        )
        assertEquals(listOf(DiffStatus.EQUAL, DiffStatus.REMOVED, DiffStatus.ADDED), result)
    }

    @Test
    fun `added and removed headings`() {
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.ADDED, DiffStatus.EQUAL),
            statuses("# A\n\ntext\n", "# A\n\n## New section\n\ntext\n"),
        )
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.REMOVED, DiffStatus.EQUAL),
            statuses("# A\n\n## Old\n\ntext\n", "# A\n\ntext\n"),
        )
    }

    @Test
    fun `soft wrapping does not count as a change`() {
        val diff = engine.diff("one two three four\n", "one two\nthree four\n")
        assertFalse(diff.hasChanges)
    }

    @Test
    fun `ignore whitespace option`() {
        assertEquals(listOf(DiffStatus.MODIFIED), statuses("a `x+y` b", "a `x + y` b"))
        assertEquals(
            listOf(DiffStatus.EQUAL),
            statuses("a `x+y` b", "a `x + y` b", DiffOptions(ignoreWhitespace = true)),
        )
    }

    @Test
    fun `nested list items are separate blocks`() {
        val left = """
            - one
            - two
                - two a
            - three
        """
        val right = """
            - one
            - two
                - two a
                - two b
            - three
        """
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.EQUAL, DiffStatus.EQUAL, DiffStatus.ADDED, DiffStatus.EQUAL),
            statuses(left, right),
        )
        val added = engine.diff(right.trimIndent(), right.trimIndent()).blocks[3].right!!
        assertEquals(2, added.context.listDepth)
    }

    @Test
    fun `renumbering an ordered list is not a change`() {
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.EQUAL),
            statuses("1. a\n2. b\n", "3. a\n4. b\n"),
        )
    }

    @Test
    fun `checking a task is a modification`() {
        assertEquals(listOf(DiffStatus.MODIFIED), statuses("- [ ] write tests\n", "- [x] write tests\n"))
    }

    @Test
    fun `table rows are compared one by one`() {
        val left = """
            | Name | Size |
            | --- | --- |
            | a | 1 |
            | b | 2 |
        """
        val right = """
            | Name | Size |
            | --- | --- |
            | a | 1 |
            | b | 3 |
            | c | 4 |
        """
        val blocks = engine.diff(left.trimIndent(), right.trimIndent()).blocks
        assertTrue(blocks.all { it.left?.kind ?: it.right?.kind == BlockKind.TABLE_ROW })
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.EQUAL, DiffStatus.MODIFIED, DiffStatus.ADDED),
            blocks.map { it.status },
        )
        assertEquals("b 3", blocks[2].right!!.plainText)
    }

    @Test
    fun `table rows pair by their first cell`() {
        val left = "| Stage | State |\n| --- | --- |\n| M1 | planned |\n| M2 | planned |\n"
        val right = "| Stage | State |\n| --- | --- |\n| M1 | done |\n| M2 | in progress |\n| M3 | planned |\n"
        val blocks = engine.diff(left, right).blocks
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.MODIFIED, DiffStatus.MODIFIED, DiffStatus.ADDED),
            blocks.map { it.status },
        )
        assertEquals("M2 in progress", blocks[2].right!!.plainText)
    }

    @Test
    fun `code blocks are compared line by line`() {
        val left = "```kotlin\nval a = 1\nval b = 2\n```\n"
        val right = "```kotlin\nval a = 1\nval b = 3\nval c = 4\n```\n"
        val blocks = engine.diff(left, right).blocks
        assertEquals(
            listOf(BlockKind.CODE_FENCE, BlockKind.CODE_LINE, BlockKind.CODE_LINE, BlockKind.CODE_LINE),
            blocks.map { (it.right ?: it.left)!!.kind },
        )
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.EQUAL, DiffStatus.MODIFIED, DiffStatus.ADDED),
            blocks.map { it.status },
        )
        assertEquals("kotlin", blocks[1].left!!.language)
    }

    @Test
    fun `changing the code language modifies only the fence`() {
        assertEquals(
            listOf(DiffStatus.MODIFIED, DiffStatus.EQUAL),
            statuses("```js\nx()\n```\n", "```ts\nx()\n```\n"),
        )
    }

    @Test
    fun `moved paragraph shows as removed and added`() {
        val left = "Alpha paragraph.\n\nBeta paragraph.\n\nGamma paragraph.\n"
        val right = "Beta paragraph.\n\nGamma paragraph.\n\nAlpha paragraph.\n"
        val result = engine.diff(left, right).blocks.map { it.status }
        assertEquals(listOf(DiffStatus.REMOVED, DiffStatus.EQUAL, DiffStatus.EQUAL, DiffStatus.ADDED), result)
    }

    @Test
    fun `front matter lines are blocks`() {
        val left = "---\ntitle: A\ntags: x\n---\n# Doc\n"
        val right = "---\ntitle: B\ntags: x\n---\n# Doc\n"
        val blocks = engine.diff(left, right).blocks
        assertEquals(
            listOf(BlockKind.CODE_FENCE, BlockKind.FRONT_MATTER, BlockKind.FRONT_MATTER, BlockKind.HEADING),
            blocks.map { it.left!!.kind },
        )
        assertEquals(
            listOf(DiffStatus.EQUAL, DiffStatus.MODIFIED, DiffStatus.EQUAL, DiffStatus.EQUAL),
            blocks.map { it.status },
        )
        assertEquals(4, blocks[3].left!!.source.startLine)
    }

    @Test
    fun `quotes keep their depth`() {
        val blocks = engine.diff("> quoted\n", "> quoted\n\nnot quoted\n").blocks
        assertEquals(1, blocks[0].left!!.context.quoteDepth)
        assertEquals(DiffStatus.ADDED, blocks[1].status)
        assertEquals(0, blocks[1].right!!.context.quoteDepth)
    }

    @Test
    fun `changed link target is a modification`() {
        assertEquals(
            listOf(DiffStatus.MODIFIED),
            statuses("See [docs](https://a.example).", "See [docs](https://b.example)."),
        )
    }

    @Test
    fun `crlf and lf are the same`() {
        assertFalse(engine.diff("# A\r\n\r\ntext\r\n", "# A\n\ntext\n").hasChanges)
    }

    @Test
    fun `source ranges point at the block`() {
        val md = "# Title\n\nFirst line\nsecond line\n\n- item\n"
        val blocks = BlockExtractor.extract(md)
        val paragraph = blocks[1]
        assertEquals(2, paragraph.source.startLine)
        assertEquals(4, paragraph.source.endLine)
        assertEquals("First line\nsecond line", md.substring(paragraph.source.startOffset, paragraph.source.endOffset))
        val item = blocks[2]
        assertEquals("- item", md.substring(item.source.startOffset, item.source.endOffset))
    }

    @Test
    fun `empty sides`() {
        assertEquals(listOf(DiffStatus.ADDED, DiffStatus.ADDED), statuses("", "# A\n\nb\n"))
        assertEquals(listOf(DiffStatus.REMOVED), statuses("text\n", ""))
        assertTrue(engine.diff("", "").blocks.isEmpty())
    }

    @Test
    fun `large documents diff quickly`() {
        val left = (1..5000).joinToString("\n\n") { "Paragraph number $it with some words." }
        val right = (1..5000).joinToString("\n\n") { if (it % 50 == 0) "Paragraph number $it with other words." else "Paragraph number $it with some words." }
        val start = System.nanoTime()
        val diff = engine.diff(left, right)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(100, diff.changes.size)
        assertTrue(ms < 5_000, "took $ms ms")
    }

    @Test
    fun `changed reference definition modifies every block that uses its label`() {
        val left = "Docs: [guide][g].\n\nPlain text.\n\n- See [guide][g]\n\n[g]: https://example.com/v1\n"
        val right = left.replace("/v1", "/v2")
        val diff = engine.diff(left, right)
        assertEquals(listOf(DiffStatus.MODIFIED, DiffStatus.EQUAL, DiffStatus.MODIFIED), diff.blocks.map { it.status })
        // The visible text is equal, so the word diff has nothing to mark.
        assertTrue(diff.changes.all { b -> b.inline.all { it.status == DiffStatus.EQUAL } })
    }

    @Test
    fun `link url change with equal text is a modification`() {
        assertEquals(
            listOf(DiffStatus.MODIFIED),
            statuses("See [repo](https://a.example/x).", "See [repo](https://a.example/y)."),
        )
    }

    @Test
    fun `block keys carry resolved link targets`() {
        val blocks = BlockExtractor.extract("A [x][r] and ![i](img.png)\n\n[r]: https://e.example\n")
        assertTrue(blocks.single().key.endsWith(" -> https://e.example img.png"), blocks.single().key)
    }

    @Test
    fun `block formulas are recognized in quotes and inside list items`() {
        val blocks = BlockExtractor.extract("> \$\$\n> a + b\n> \$\$\n\n- item\n\n  \$\$\n  x^2\n  \$\$\n")
        val math = blocks.single { it.kind == BlockKind.MATH }
        assertEquals("a + b", math.plainText)
        assertEquals(1, math.context.quoteDepth)
        assertEquals(SourceRange(0, 3, 2, 17), math.source)
        assertEquals(listOf(1), math.lines.map { it.source.startLine })
        // Inside a list item the formula is part of the item, kept whole by the word diff.
        val item = blocks.single { it.kind == BlockKind.LIST_ITEM }
        assertEquals("item \$\$x^2\$\$", item.plainText)
        assertEquals(listOf(5..11), item.atoms)
    }

    @Test
    fun `dollar signs that do not delimit a formula stay text`() {
        for (text in listOf("\$\$\$ x \$\$\$", "a \$ b", "\$x \$", "costs \$5 and \$6")) {
            val block = BlockExtractor.extract(text).single()
            assertEquals(BlockKind.PARAGRAPH, block.kind, text)
            assertTrue(block.atoms.isEmpty(), text)
        }
        val inline = BlockExtractor.extract("Mass \$\$E = mc^2\$\$ and \$x\$.").single()
        assertEquals("Mass \$\$E = mc^2\$\$ and \$x\$.", inline.plainText)
        assertEquals(listOf(5..16, 22..24), inline.atoms)
    }

    @Test
    fun `a moved paragraph links its two ends`() {
        val diff = engine.diff("First.\n\nMoved text here.\n\nSecond.\n\nThird.\n", "First.\n\nSecond.\n\nThird.\n\nMoved text here.\n")
        val removed = diff.blocks.indexOfFirst { it.status == DiffStatus.REMOVED }
        val added = diff.blocks.indexOfFirst { it.status == DiffStatus.ADDED }
        assertEquals(added, diff.blocks[removed].movedTo)
        assertEquals(removed, diff.blocks[added].movedFrom)
        assertEquals(null, diff.blocks[removed].movedFrom)
        assertTrue(diff.blocks.none { it.status == DiffStatus.MODIFIED })
    }

    @Test
    fun `code lines and edited blocks are not moves`() {
        val code = engine.diff("```\na\n}\nb\n```\n", "```\nb\n}\na\n```\n")
        assertTrue(code.blocks.none { it.movedTo != null || it.movedFrom != null })
        // Two equal removed blocks and one added: only the first removed one moves.
        val twice = engine.diff("Same words.\n\nOther.\n\nSame words.\n\nEnd.\n", "Other.\n\nEnd.\n\nSame words.\n")
        assertEquals(1, twice.blocks.count { it.movedTo != null })
        assertEquals(1, twice.blocks.count { it.movedFrom != null })
    }
}

