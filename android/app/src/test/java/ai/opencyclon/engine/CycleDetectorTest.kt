package ai.opencyclon.engine

import ai.opencyclon.action.Action
import org.junit.Assert.*
import org.junit.Test

class CycleDetectorTest {
    private fun e(t: String, screen: Int?) = CycleDetector.Entry("tap", t, screen)

    private fun loops(cycle: List<CycleDetector.Entry>, times: Int): Boolean {
        val d = CycleDetector()
        repeat(times) { cycle.forEach(d::add) }
        return d.looping()
    }

    @Test fun `cycles of length 1 to 4 repeated three times are detected`() {
        for (length in 1..4) {
            val cycle = (1..length).map { e("t$it", it) }
            assertFalse("length $length twice", loops(cycle, 2))
            assertTrue("length $length three times", loops(cycle, 3))
        }
    }

    @Test fun `the third repeat is detected as it completes, not before`() {
        val d = CycleDetector()
        val cycle = listOf(e("a", 1), e("b", 2), e("c", 3))
        repeat(2) { cycle.forEach(d::add) }
        d.add(cycle[0]); d.add(cycle[1])
        assertFalse(d.looping())
        d.add(cycle[2])
        assertTrue(d.looping())
    }

    @Test fun `a changing screen is not a cycle`() {
        val d = CycleDetector()
        repeat(3) { d.add(e("a", it)); d.add(e("b", 10)) }
        assertFalse(d.looping())
    }

    @Test fun `an unknown screen is never a cycle`() {
        assertFalse(loops(listOf(e("a", null)), 5))
    }

    @Test fun `different targets are not a cycle`() {
        val d = CycleDetector()
        (1..12).forEach { d.add(e("t$it", 1)) }
        assertFalse(d.looping())
    }

    @Test fun `waits and observations are not members`() {
        assertNull(CycleDetector.targetOf(Action.Wait()))
        assertNull(CycleDetector.targetOf(Action.Note("x")))
        assertNull(CycleDetector.targetOf(Action.ListApps()))
        assertNull(CycleDetector.targetOf(Action.AskUser("q")))
        assertEquals("k", CycleDetector.targetOf(Action.Tap(1, 2, index = 3, targetKey = "k")))
        assertEquals("25,10", CycleDetector.targetOf(Action.Tap(500, 200)))
        assertEquals("back", CycleDetector.targetOf(Action.KeyEvent("back")))
    }

    @Test fun `the sentence`() {
        assertEquals("I was going in circles, so I stopped. Nothing more was sent.", CycleDetector.MESSAGE)
    }
}
