package pl.mdreview.ide

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import pl.mdreview.api.DiffOptions
import pl.mdreview.api.SizeLimit

class MarkdownReviewSettingsTest : BasePlatformTestCase() {

    fun `test defaults`() {
        val settings = MarkdownReviewSettings()
        assertEquals(DefaultViewer.RENDERED_SIDE_BY_SIDE, settings.defaultViewer)
        assertFalse(settings.ignoreWhitespace)
        assertTrue(settings.collapseUnchanged)
        assertEquals(20_000, settings.largeFileLines)
        assertEquals(2_048, settings.largeFileKilobytes)
        assertFalse(settings.loadRemoteImages)
        assertTrue(settings.renderMermaid)
    }

    fun `test state survives serialization`() {
        val settings = MarkdownReviewSettings().apply {
            defaultViewer = DefaultViewer.TEXT
            ignoreWhitespace = true
            collapseUnchanged = false
            largeFileLines = 500
            largeFileKilobytes = 64
            loadRemoteImages = true
            renderMermaid = false
            appliedDefaultViewer = DefaultViewer.TEXT
        }
        val element = XmlSerializer.serialize(settings.state)
        val restored = MarkdownReviewSettings()
        restored.loadState(XmlSerializer.deserialize(element, MarkdownReviewSettings.SettingsState::class.java))

        assertEquals(DefaultViewer.TEXT, restored.defaultViewer)
        assertTrue(restored.ignoreWhitespace)
        assertFalse(restored.collapseUnchanged)
        assertEquals(500, restored.largeFileLines)
        assertEquals(64, restored.largeFileKilobytes)
        assertTrue(restored.loadRemoteImages)
        assertFalse(restored.renderMermaid)
        assertEquals(DefaultViewer.TEXT, restored.appliedDefaultViewer)
    }

    fun `test settings feed the renderer options, the large-file limit and the page CSP`() {
        val settings = MarkdownReviewSettings().apply {
            ignoreWhitespace = true
            collapseUnchanged = false
            loadRemoteImages = true
            renderMermaid = false
            largeFileLines = 100
            largeFileKilobytes = 3
        }
        assertEquals(DiffOptions(ignoreWhitespace = true, collapseUnchanged = false, loadRemoteImages = true, renderMermaid = false),
            settings.options(),
        )
        assertEquals(SizeLimit(100, 3 * 1024L), LargeFiles.limit(settings))
        assertEquals(SizeLimit.DEFAULT, LargeFiles.limit(MarkdownReviewSettings()))
        assertEquals("file: data: https: http:", HtmlPage.imgSources(true))
        assertEquals("file: data:", HtmlPage.imgSources(false))
    }

    fun `test legacy toggle values are migrated and removed`() {
        val props = PropertiesComponent.getInstance()
        props.setValue(MarkdownReviewSettings.LEGACY_IGNORE_WHITESPACE, true, false)
        props.setValue(MarkdownReviewSettings.LEGACY_COLLAPSE_UNCHANGED, false, true)
        try {
            val settings = MarkdownReviewSettings()
            settings.noStateLoaded()

            assertTrue(settings.ignoreWhitespace)
            assertFalse(settings.collapseUnchanged)
            assertFalse(props.isValueSet(MarkdownReviewSettings.LEGACY_IGNORE_WHITESPACE))
            assertFalse(props.isValueSet(MarkdownReviewSettings.LEGACY_COLLAPSE_UNCHANGED))
        } finally {
            props.unsetValue(MarkdownReviewSettings.LEGACY_IGNORE_WHITESPACE)
            props.unsetValue(MarkdownReviewSettings.LEGACY_COLLAPSE_UNCHANGED)
        }
    }

    fun `test missing legacy keys keep the defaults`() {
        val settings = MarkdownReviewSettings()
        settings.noStateLoaded()
        assertFalse(settings.ignoreWhitespace)
        assertTrue(settings.collapseUnchanged)
    }

    fun `test settings page applies values to the service`() {
        val settings = MarkdownReviewSettings.getInstance()
        val saved = XmlSerializer.serialize(settings.state)
        val configurable = MarkdownReviewConfigurable()
        try {
            configurable.createComponent()
            configurable.reset()
            assertFalse(configurable.isModified)
            settings.ignoreWhitespace = !settings.ignoreWhitespace
            assertTrue(configurable.isModified)
            configurable.reset()
            assertFalse(configurable.isModified)
        } finally {
            configurable.disposeUIResources()
            settings.loadState(XmlSerializer.deserialize(saved, MarkdownReviewSettings.SettingsState::class.java))
        }
    }
}
