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
        assertTrue(settings.renderMath)
        assertEquals(DiffOptions.DEFAULT_PAIRING_THRESHOLD, settings.pairingThreshold)
        assertEquals("", settings.customCssPath)
    }

    fun `test pairing threshold stays in range`() {
        val settings = MarkdownReviewSettings()
        settings.pairingThreshold = 2.0
        assertEquals(DiffOptions.MAX_PAIRING_THRESHOLD, settings.pairingThreshold)
        settings.pairingThreshold = -1.0
        assertEquals(DiffOptions.MIN_PAIRING_THRESHOLD, settings.pairingThreshold)
        assertEquals(0.2, DiffOptions.MIN_PAIRING_THRESHOLD)
        assertEquals(0.8, DiffOptions.MAX_PAIRING_THRESHOLD)
    }

    fun `test saved pairing threshold outside the range is clamped`() {
        val settings = MarkdownReviewSettings()
        settings.loadState(MarkdownReviewSettings.SettingsState().apply { pairingThreshold = 0.05 })
        assertEquals(0.2, settings.pairingThreshold)
        assertEquals(0.2, settings.options().pairingThreshold)
        settings.loadState(MarkdownReviewSettings.SettingsState().apply { pairingThreshold = 0.95 })
        assertEquals(0.8, settings.pairingThreshold)
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
            renderMath = false
            pairingThreshold = 0.7
            customCssPath = " /home/me/docs.css "
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
        assertFalse(restored.renderMath)
        assertEquals(0.7, restored.pairingThreshold)
        assertEquals("/home/me/docs.css", restored.customCssPath)
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
            pairingThreshold = 0.3
        }
        assertEquals(
            DiffOptions(
                ignoreWhitespace = true,
                collapseUnchanged = false,
                loadRemoteImages = true,
                renderMermaid = false,
                pairingThreshold = 0.3,
            ),
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
