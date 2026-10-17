package pl.mdreview.ide

import pl.mdreview.api.SizeLimit

/** Where the IDE layer reads the large-file limit (NFR-4). */
object LargeFiles {
    /** The limit in force, from Settings | Tools | Markdown Review. */
    fun limit(settings: MarkdownReviewSettings = MarkdownReviewSettings.getInstance()): SizeLimit =
        SizeLimit(settings.largeFileLines, settings.largeFileKilobytes * 1024L)
}
