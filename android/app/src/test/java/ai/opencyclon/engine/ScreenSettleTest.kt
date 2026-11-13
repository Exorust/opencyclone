package ai.opencyclon.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenSettleTest {
    private var now = 0L
    private val sleeps = mutableListOf<Long>()

    private var pictureTimes = mutableListOf<Long>()

    private fun settle(
        minMs: Long, picture: ((Long) -> Long?)? = null, pictureCostMs: Long = 0,
        reads: (Long) -> ScreenSettle.Snapshot?,
    ): Long = runBlocking {
        ScreenSettle.await(minMs, read = { reads(now) }, now = { now }, sleep = { sleeps += it; now += it },
            picture = picture?.let { p -> { pictureTimes += now; p(now).also { now += pictureCostMs } } })
    }

    private fun snap(hash: Int, changes: Long = 0, loading: Boolean = false) = ScreenSettle.Snapshot(hash, changes, loading)

    @Test fun `a screen that is quiet at once waits the minimum`() {
        assertEquals(300L, settle(ScreenSettle.MIN_MS) { snap(1) })
        // The remainder first, so the deciding pair (50 ms and 300 ms) is a full poll apart.
        assertEquals(listOf(50L, 250L), sleeps)
    }

    @Test fun `a screen that settles late waits until two reads agree`() {
        // Content changes until 1800 ms, then stays.
        val waited = settle(ScreenSettle.MIN_MS) { t -> snap(if (t < 1800) t.toInt() else 99) }
        assertTrue("waited $waited", waited in 1800L..2300L)
    }

    @Test fun `a spinner keeps the screen from being quiet`() {
        val waited = settle(ScreenSettle.MIN_MS) { t -> snap(1, loading = t < 1800) }
        assertTrue("waited $waited", waited in 1800L..2300L)
    }

    @Test fun `a change event between equal lists is not quiet`() {
        val waited = settle(ScreenSettle.MIN_MS) { t -> snap(1, changes = if (t < 1000) t else 1000) }
        assertTrue("waited $waited", waited in 1000L..1500L)
    }

    @Test fun `a screen that never stops changing ends at three seconds`() {
        var i = 0
        assertEquals(3000L, settle(ScreenSettle.MIN_MS) { snap(i++) })
        assertEquals(3000L, sleeps.sum())
    }

    @Test fun `after opening an app the minimum is 800 ms`() {
        assertEquals(800L, ScreenSettle.minFor("open_app"))
        assertEquals(800L, ScreenSettle.minFor("open_link"))
        assertEquals(300L, ScreenSettle.minFor("tap"))
        assertEquals(800L, settle(ScreenSettle.minFor("open_app")) { snap(1) })
    }

    @Test fun `text that changes every 100 ms is never quiet, even near the minimum`() {
        // Reads 50 ms apart would often agree; the pair that decides is a full poll apart (never_still on the phone).
        assertEquals(3000L, settle(ScreenSettle.MIN_MS) { t -> snap((t / 100).toInt()) })
    }

    @Test fun `a drawing animation with equal element lists is not quiet`() {
        val waited = settle(ScreenSettle.MIN_MS, picture = { t -> t / 100 }) { snap(1) }
        assertEquals(3000L, waited)
    }

    @Test fun `a drawing animation that stops is quiet two pictures later`() {
        val waited = settle(ScreenSettle.MIN_MS, picture = { t -> if (t < 1000) t else 1000 }) { snap(1) }
        assertTrue("waited $waited", waited in 1000L..1500L)
    }

    @Test fun `pictures are taken only from one poll before the minimum and never while a spinner shows`() {
        settle(ScreenSettle.MIN_MS, picture = { 7L }) { snap(1) }
        assertEquals(listOf(50L, 300L), pictureTimes)
        pictureTimes.clear(); now = 0; sleeps.clear()
        settle(ScreenSettle.MIN_AFTER_OPEN_MS, picture = { 7L }) { snap(1) }
        assertEquals(listOf(550L, 800L), pictureTimes)
        pictureTimes.clear(); now = 0; sleeps.clear()
        settle(ScreenSettle.MIN_MS, picture = { 7L }) { t -> snap(1, loading = t < 1800) }
        assertTrue(pictureTimes.all { it >= 1800 })
    }

    @Test fun `the time a picture takes does not stretch the polls`() {
        val waited = settle(ScreenSettle.MIN_MS, picture = { 7L }, pictureCostMs = 60) { snap(1) }
        // Reads at 50 and 300; the second picture's 60 ms is the only time added to the minimum.
        assertEquals(360L, waited)
        assertEquals(listOf(50L, 300L), pictureTimes)
    }

    @Test fun `a missing picture on one side does not decide`() {
        assertTrue(ScreenSettle.agree(snap(1).copy(picture = 5), snap(1)))
        assertFalse(ScreenSettle.agree(snap(1).copy(picture = 5), snap(1).copy(picture = 6)))
        assertFalse(ScreenSettle.agree(snap(1).copy(picture = 5), snap(2).copy(picture = 5)))
    }

    @Test fun `nothing to read waits the minimum`() {
        assertEquals(300L, settle(ScreenSettle.MIN_MS) { null })
    }

    @Test fun `a clock that stands still still ends`() = runBlocking {
        var i = 0
        val waited = ScreenSettle.await(300, read = { snap(i++) }, now = { 5L }, sleep = { })
        assertEquals(3000L, waited)
    }
}
