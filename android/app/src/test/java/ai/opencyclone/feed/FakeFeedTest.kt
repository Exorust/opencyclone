package ai.opencyclone.feed

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeFeedTest {
    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]")
    private val forbiddenNames = listOf("Alex", "Thomas", "Barcelona", "Cyclone", "OpenCyclone", "Tern")

    private fun checkText(t: String) {
        assertFalse("emoji in: $t", emoji.containsMatchIn(t))
        assertFalse("exclamation in: $t", t.contains('!'))
        forbiddenNames.forEach { assertFalse("$it in: $t", t.contains(it)) }
    }

    @Test fun sampleObeysTheTextRules() = runTest {
        val f = FakeFeed.sample()
        val live = f.live.value
        assertTrue(live.status.length <= 60)
        assertTrue(live.status.endsWith("."))
        checkText(live.status)
        assertTrue(live.running.size <= 1)
        live.running.forEach { r -> assertTrue(r.caption.split(' ').size in 3..6); checkText(r.caption) }
        live.approval?.let { checkText(it.heading); assertTrue(it.heading.endsWith("?")) }

        val items = f.today().first()
        assertTrue(items.isNotEmpty())
        assertEquals(items.sortedBy { it.at }, items) // oldest first
        assertEquals(items.size, items.map { it.id }.toSet().size)
        items.forEach { i ->
            listOfNotNull(i.text, i.title, i.detail, i.actionWord).forEach(::checkText)
            if (i.kind == ThreadKind.SENTENCE) assertTrue("sentence ends: ${i.text}", i.text.endsWith("."))
            if (i.kind == ThreadKind.RECEIPT) assertFalse("receipt is a phrase: ${i.text}", i.text.endsWith("."))
        }
        // every kind used on the home page appears at least once
        val kinds = items.map { it.kind }.toSet()
        listOf(ThreadKind.OWNER, ThreadKind.NOTIFICATION, ThreadKind.SENTENCE, ThreadKind.APPROVAL,
            ThreadKind.RECEIPT, ThreadKind.PROGRESS, ThreadKind.RESULT, ThreadKind.SHARED_APP).forEach {
            assertTrue("kind $it", it in kinds)
        }
    }

    @Test fun commandsAreRecorded() = runTest {
        val f = FakeFeed()
        assertEquals("All caught up.", f.live.value.status)
        f.submit("hello")
        assertTrue(f.approve(4))
        f.approveResult = false
        assertFalse(f.approve(5))
        f.reject(6); f.stop(7)
        assertEquals(listOf("hello"), f.submitted)
        assertEquals(listOf(4L, 5L), f.approved)
        assertEquals(listOf(6L), f.rejected)
        assertEquals(listOf(7L), f.stopped)
        f.state.value = LiveState(status = "Busy.")
        assertEquals("Busy.", f.live.value.status)
    }

    @Test fun locatorAcceptsTheFake() {
        val f = FakeFeed()
        FeedLocator.feed = f
        FeedLocator.commands = f
        assertTrue(FeedLocator.isReady)
    }
}
