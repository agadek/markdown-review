package pl.mdreview.ide

/**
 * What the page can ask for a block: the buttons (FR-12), where revert writes the left version into the right side
 * and apply the right version into the left, and saving a block's edited source on one side (FR-13).
 */
enum class BlockAction(val id: String) {
    REVERT("revert"),
    APPLY("apply"),
    EDIT_LEFT("edit-left"),
    EDIT_RIGHT("edit-right"),
    ;

    /** For a source edit, the side it writes: 0 left, 1 right; `null` for a button. */
    val editSide: Int?
        get() = when (this) {
            EDIT_LEFT -> 0
            EDIT_RIGHT -> 1
            else -> null
        }
}

/**
 * Which block buttons a page shows, which sides a double-click edits, and the stamp of the documents it was
 * rendered from.
 *
 * @property stamp the documents' `modificationStamp`s at render time, `left:right`; every click sends it back,
 *   so a click on a page older than the documents is refused instead of editing the wrong lines.
 * @property editLeft a double-click on a block of the left side opens its source for editing (FR-13).
 * @property editRight the same for the right side.
 */
data class BlockActions(
    val revert: Boolean,
    val apply: Boolean,
    val stamp: String,
    val editLeft: Boolean = false,
    val editRight: Boolean = false,
) {
    /** Whether the page shows any button. */
    val any: Boolean get() = revert || apply

    /** Whether a block of either side can be edited. */
    val edits: Boolean get() = editLeft || editRight

    fun allows(action: BlockAction) = when (action) {
        BlockAction.REVERT -> revert
        BlockAction.APPLY -> apply
        BlockAction.EDIT_LEFT -> editLeft
        BlockAction.EDIT_RIGHT -> editRight
    }

    companion object {
        val NONE = BlockActions(revert = false, apply = false, stamp = "")
    }
}

/**
 * A click on a block button, the page's message `edit|<action>|<idx>|<stamp>`, or a saved source edit,
 * `edit|edit-<side>|<idx>|<stamp>|<markdown>`, where [source] is the rest of the message, `|` included.
 */
data class BlockActionRequest(val action: BlockAction, val idx: Int, val stamp: String, val source: String? = null) {
    companion object {
        fun parse(message: String): BlockActionRequest? {
            val parts = message.split('|', limit = 5)
            if (parts.size < 4 || parts[0] != "edit") return null
            val action = BlockAction.entries.firstOrNull { it.id == parts[1] } ?: return null
            val idx = parts[2].toIntOrNull() ?: return null
            if ((action.editSide != null) != (parts.size == 5)) return null
            return BlockActionRequest(action, idx, parts[3], parts.getOrNull(4))
        }
    }
}
