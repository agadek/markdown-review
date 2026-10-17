package pl.mdreview.ide

import pl.mdreview.api.SizeLimit

/** Where the IDE layer reads the large-file limit (NFR-4). */
object LargeFiles {
    /**
     * The limit in force. Fixed at [SizeLimit.DEFAULT] until the settings page (P2.2) stores its own
     * `largeFileLines` / `largeFileKilobytes`; this is the one place to read them from.
     */
    fun limit(): SizeLimit = SizeLimit.DEFAULT
}
