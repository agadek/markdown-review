package pl.mdreview.ide

import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffTool
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.impl.DiffSettingsHolder.DiffSettings
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffPlaces
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DefaultViewerOrderTest : BasePlatformTestCase() {

    private val sideBySide = RenderedSideBySideTool::class.java.canonicalName
    private val merged = RenderedMergedTool::class.java.canonicalName
    private val simple = SimpleDiffTool::class.java.canonicalName
    private val unified = UnifiedDiffTool::class.java.canonicalName

    private lateinit var savedOrders: Map<String, List<String>>
    private lateinit var savedViewer: Pair<DefaultViewer, DefaultViewer?>

    override fun setUp() {
        super.setUp()
        savedOrders = DefaultViewerOrder.KNOWN_PLACES.associateWith { DiffSettings.getSettings(it).diffToolsOrder }
        val settings = MarkdownReviewSettings.getInstance()
        savedViewer = settings.defaultViewer to settings.appliedDefaultViewer
    }

    override fun tearDown() {
        try {
            savedOrders.forEach { (place, order) -> DiffSettings.getSettings(place).diffToolsOrder = order }
            val settings = MarkdownReviewSettings.getInstance()
            settings.defaultViewer = savedViewer.first
            settings.appliedDefaultViewer = savedViewer.second
        } finally {
            super.tearDown()
        }
    }

    /** The tools in the order a diff window at [place] tries them, as `DiffRequestProcessor` computes it. */
    private fun effectiveOrder(place: String): List<DiffTool> =
        DiffRequestProcessor.getToolOrderFromSettings(DiffSettings.getSettings(place), DiffManagerEx.getInstance().diffTools)

    private fun firstOf(place: String, vararg candidates: Class<*>): DiffTool =
        effectiveOrder(place).first { tool -> candidates.any { it.isInstance(tool) } }

    fun `test rendered side by side moves to the front`() {
        assertEquals(listOf(sideBySide, simple, unified), DefaultViewerOrder.reorder(listOf(simple, unified), DefaultViewer.RENDERED_SIDE_BY_SIDE))
        assertEquals(listOf(sideBySide, merged, simple), DefaultViewerOrder.reorder(listOf(merged, sideBySide, simple), DefaultViewer.RENDERED_SIDE_BY_SIDE))
    }

    fun `test rendered merged moves to the front`() {
        assertEquals(listOf(merged, sideBySide, simple), DefaultViewerOrder.reorder(listOf(sideBySide, simple), DefaultViewer.RENDERED_MERGED))
    }

    fun `test text puts a text viewer before the rendered ones`() {
        assertEquals(listOf(simple, sideBySide, merged), DefaultViewerOrder.reorder(emptyList(), DefaultViewer.TEXT))
        assertEquals(listOf(unified, sideBySide, merged), DefaultViewerOrder.reorder(listOf(sideBySide, unified), DefaultViewer.TEXT))
    }

    fun `test Show Rendered Diff keeps the rendered viewer last chosen in its window`() {
        assertEquals(listOf(merged, simple, sideBySide), DefaultViewerOrder.preferRendered(listOf(simple, merged, sideBySide), DefaultViewer.RENDERED_SIDE_BY_SIDE))
        assertEquals(listOf(sideBySide, simple, merged), DefaultViewerOrder.preferRendered(listOf(simple, sideBySide, merged), DefaultViewer.RENDERED_MERGED))
    }

    fun `test Show Rendered Diff starts with the rendered default viewer`() {
        assertEquals(listOf(sideBySide), DefaultViewerOrder.preferRendered(emptyList(), DefaultViewer.TEXT))
        assertEquals(listOf(sideBySide, simple), DefaultViewerOrder.preferRendered(listOf(simple), DefaultViewer.RENDERED_SIDE_BY_SIDE))
        assertEquals(listOf(merged, simple), DefaultViewerOrder.preferRendered(listOf(simple), DefaultViewer.RENDERED_MERGED))
    }

    fun `test plugin viewers are registered`() {
        val tools = DiffManagerEx.getInstance().diffTools
        assertTrue(tools.any { it is RenderedSideBySideTool })
        assertTrue(tools.any { it is RenderedMergedTool })
    }

    fun `test each setting decides the first viewer of every known place`() {
        val viewers = arrayOf(RenderedSideBySideTool::class.java, RenderedMergedTool::class.java, SimpleDiffTool::class.java, UnifiedDiffTool::class.java)
        for (place in DefaultViewerOrder.KNOWN_PLACES) {
            DefaultViewerOrder.apply(DefaultViewer.TEXT)
            assertTrue(place, firstOf(place, *viewers) is SimpleDiffTool || firstOf(place, *viewers) is UnifiedDiffTool)

            DefaultViewerOrder.apply(DefaultViewer.RENDERED_SIDE_BY_SIDE)
            assertInstanceOf(firstOf(place, *viewers), RenderedSideBySideTool::class.java)

            DefaultViewerOrder.apply(DefaultViewer.RENDERED_MERGED)
            assertInstanceOf(firstOf(place, *viewers), RenderedMergedTool::class.java)
        }
    }

    fun `test the user's choice in a diff window is kept until the setting changes`() {
        val settings = MarkdownReviewSettings.getInstance()
        settings.defaultViewer = DefaultViewer.RENDERED_SIDE_BY_SIDE
        settings.appliedDefaultViewer = null
        DefaultViewerOrder.syncWithSettings(settings)
        assertEquals(sideBySide, DiffSettings.getSettings(DiffPlaces.COMMIT_DIALOG).diffToolsOrder.first())

        // What the diff window does when the user picks the text viewer.
        val commit = DiffSettings.getSettings(DiffPlaces.COMMIT_DIALOG)
        commit.diffToolsOrder = listOf(simple) + commit.diffToolsOrder.filter { it != simple }

        DefaultViewerOrder.syncWithSettings(settings)
        assertEquals(simple, DiffSettings.getSettings(DiffPlaces.COMMIT_DIALOG).diffToolsOrder.first())

        settings.defaultViewer = DefaultViewer.RENDERED_MERGED
        DefaultViewerOrder.syncWithSettings(settings)
        assertEquals(merged, DiffSettings.getSettings(DiffPlaces.COMMIT_DIALOG).diffToolsOrder.first())
        assertEquals(DefaultViewer.RENDERED_MERGED, settings.appliedDefaultViewer)
    }
}
