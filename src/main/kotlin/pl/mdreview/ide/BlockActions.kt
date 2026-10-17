package pl.mdreview.ide

/** Per-block buttons (FR-12): revert writes the left version into the right side, apply the right version into the left. */
enum class BlockAction(val id: String) {
    REVERT("revert"),
    APPLY("apply"),
}

/**
 * Which block buttons a page shows, and the stamp of the documents it was rendered from.
 *
 * @property stamp the documents' `modificationStamp`s at render time, `left:right`; every click sends it back,
 *   so a click on a page older than the documents is refused instead of editing the wrong lines.
 */
data class BlockActions(val revert: Boolean, val apply: Boolean, val stamp: String) {
    val any: Boolean get() = revert || apply

    fun allows(action: BlockAction) = when (action) {
        BlockAction.REVERT -> revert
        BlockAction.APPLY -> apply
    }

    companion object {
        val NONE = BlockActions(revert = false, apply = false, stamp = "")
    }
}

/** A click on a block button: the page's message `edit|<action>|<idx>|<stamp>`. */
data class BlockActionRequest(val action: BlockAction, val idx: Int, val stamp: String) {
    companion object {
        fun parse(message: String): BlockActionRequest? {
            val parts = message.split('|')
            if (parts.size != 4 || parts[0] != "edit") return null
            val action = BlockAction.entries.firstOrNull { it.id == parts[1] } ?: return null
            val idx = parts[2].toIntOrNull() ?: return null
            return BlockActionRequest(action, idx, parts[3])
        }
    }
}
