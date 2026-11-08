package pl.mdreview.ide

/**
 * Serializes page snapshots when more than one export asks for one at the same time.
 *
 * The page answers `mdr.snapshot()` with one `export|` message and has no request id, so only one request may be in
 * flight. [enqueue] returns true when the caller should send it; [complete] returns the callback for the answer and
 * whether another request is waiting, which the caller then sends.
 */
internal class SnapshotQueue<T> {

    private val pending = ArrayDeque<(T) -> Unit>()
    private var inFlight = false

    /** Adds [callback]; true when the caller must send the snapshot request now. */
    fun enqueue(callback: (T) -> Unit): Boolean = synchronized(this) {
        pending.addLast(callback)
        if (inFlight) {
            false
        } else {
            inFlight = true
            true
        }
    }

    /** The answer to the in-flight request: the callback to call and whether another request must be sent. */
    fun complete(value: T): Completion<T> = synchronized(this) {
        inFlight = false
        val callback = pending.removeFirstOrNull()
        val next = pending.isNotEmpty()
        if (next) inFlight = true
        Completion(callback, next)
    }

    data class Completion<T>(val callback: ((T) -> Unit)?, val next: Boolean)
}
