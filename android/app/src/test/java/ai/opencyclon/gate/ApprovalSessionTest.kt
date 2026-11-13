package ai.opencyclon.gate

import ai.opencyclon.action.Action
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ApprovalSessionTest {
    private val session = ApprovalSession()
    private val held = HeldAction(Action.Tap(10, 20), HoldReason.COMMIT_WORD, "Send", intArrayOf(0, 0, 10, 10),
        10, 20, ScreenFacts("com.whatsapp", "Alex Test", 3))

    private fun decide(decision: (ApprovalSession) -> Boolean): Boolean = runBlocking {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.await(7, held) }
        assertEquals(ApprovalSession.Pending(7, held), session.pending())
        assertTrue(decision(session))
        val result = withTimeout(5000) { waiting.await() }
        assertNull(session.pending())
        result
    }

    @Test fun `approve returns true`() = assertTrue(decide { it.approve(7) })

    @Test fun `reject returns false`() = assertFalse(decide { it.reject(7) })

    @Test fun `expire returns false`() = assertFalse(decide { it.expire(7) })

    @Test fun `edit returns the correction`() = runBlocking {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(7, held) }
        assertTrue(session.edit(7, " only the first two "))
        assertEquals(HoldResult.Edit("only the first two"), withTimeout(5000) { waiting.await() })
    }

    @Test fun `decision for another task is ignored`() = runBlocking {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.await(7, held) }
        assertFalse(session.approve(8))
        yield()
        assertFalse(waiting.isCompleted)
        assertTrue(session.reject(7))
        assertFalse(withTimeout(5000) { waiting.await() })
    }

    @Test fun `second await while one is pending fails fast`() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { session.await(7, held) }
        try {
            withTimeout(1000) { session.await(8, held) }
            fail("second await must fail")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(7L, session.pending()?.taskId)
        session.approve(7)
        assertTrue(first.await())
    }
}
