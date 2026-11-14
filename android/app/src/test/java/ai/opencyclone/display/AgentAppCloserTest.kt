package ai.opencyclone.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentAppCloserTest {
    @Test fun `removes every task on the hidden display`() {
        val tasks = mutableListOf(12, 11)
        val removed = mutableListOf<Int>()
        val left = AgentAppCloser.closeAll({ tasks.firstOrNull() }) { id -> removed += id; tasks.remove(id) }
        assertNull(left)
        assertEquals(listOf(12, 11), removed)
    }

    @Test fun `empty display needs no removal`() {
        assertNull(AgentAppCloser.closeAll({ null }) { error("must not remove") })
    }

    @Test fun `failed removal reports the task that stays`() {
        assertEquals(7, AgentAppCloser.closeAll({ 7 }) { false })
    }

    @Test fun `task that survives removal is reported, not removed forever`() {
        var calls = 0
        assertEquals(7, AgentAppCloser.closeAll({ 7 }) { calls++; true })
        assertEquals(1, calls)
    }
}
