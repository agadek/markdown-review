package pl.mdreview.ide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotQueueTest {

    @Test
    fun `concurrent snapshot requests are answered one at a time in order`() {
        val queue = SnapshotQueue<String?>()
        val answers = mutableListOf<String?>()

        assertTrue(queue.enqueue { answers += it })
        assertFalse(queue.enqueue { answers += it })

        val first = queue.complete("first")
        assertTrue(first.next)
        first.callback!!.invoke("first")
        assertEquals(listOf<String?>("first"), answers)

        val second = queue.complete("second")
        assertFalse(second.next)
        second.callback!!.invoke("second")
        assertEquals(listOf<String?>("first", "second"), answers)
    }

    @Test
    fun `a new request after the queue drained starts immediately`() {
        val queue = SnapshotQueue<String?>()
        assertTrue(queue.enqueue { _ -> })
        assertFalse(queue.complete("only").next)
        assertTrue(queue.enqueue { _ -> })
    }
}
