package ai.opencyclon.feed

import ai.opencyclon.db.AppDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThreadRecorderTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private var time = DAY + 10_000L
    private val pruned = mutableListOf<Long>()
    private val recorder = ThreadRecorder(db.threadItemDao(), { time }, { it - it % DAY }, { pruned += it })

    @After fun close() = db.close()

    private suspend fun today() = recorder.today().first()

    @Test fun everyKindIsStoredWithItsFields() = runBlocking {
        val owner = recorder.owner("  Send Alex the photos. ")
        val note = recorder.notification("com.whatsapp", "Alex", "Can you send the photos?")
        val sentence = recorder.sentence("Handling notifications until 14:41", taskId = 3)
        val question = recorder.question("Which Alex", 3)
        val approval = recorder.approval(3, "Send in WhatsApp to Alex?", "Send to Alex", "com.whatsapp",
            Evidence(count = 2, framePath = "/f.jpg"), canApprove = true)
        val result = recorder.result(3, "Sent Alex 2 photos.")
        val receipt = recorder.receipt(3, "com.whatsapp", "Sent", "2 photos to Alex")
        val progress = recorder.progress("Building your app", "~1 min", null, null)
        val items = today().associateBy { it.id }

        assertEquals(ThreadItem(owner, time, ThreadKind.OWNER, text = "Send Alex the photos."), items[owner])
        assertEquals(ThreadState.OPEN, items[note]!!.state)
        assertEquals("Alex", items[note]!!.title)
        assertEquals("com.whatsapp", items[note]!!.appPackage)
        assertEquals("Handling notifications until 14:41.", items[sentence]!!.text)
        assertEquals(ThreadKind.QUESTION, items[question]!!.kind)
        assertEquals("Which Alex.", items[question]!!.text)
        assertEquals(ThreadState.OPEN, items[question]!!.state)
        with(items[approval]!!) {
            assertEquals(ThreadKind.APPROVAL, kind); assertEquals(ThreadState.OPEN, state)
            assertEquals("Send to Alex", actionWord); assertEquals(Evidence(count = 2, framePath = "/f.jpg"), evidence)
            assertTrue(canApprove)
        }
        assertEquals("Sent Alex 2 photos.", items[result]!!.text)
        assertEquals("Sent", items[receipt]!!.actionWord)
        assertEquals("~1 min", items[progress]!!.detail)
        assertEquals(listOf(owner, note, sentence, question, approval, result, receipt, progress), today().map { it.id })
    }

    @Test fun stateTransitions() = runBlocking {
        val note = recorder.notification("p", "Alex", "hi")
        recorder.notificationTriaged(note, taskId = 9)
        assertEquals(ThreadState.OPEN to 9L, today().single().let { it.state to it.taskId })
        val dropped = recorder.notification("p", "Shop", "Sale")
        recorder.dropNotification(dropped)
        val summarized = recorder.notification("p", "News", "Story")
        recorder.notificationTriaged(summarized, null)
        assertEquals(listOf(note, summarized), today().map { it.id })
        assertEquals(ThreadState.DONE, recorder.get(summarized)!!.state)

        val approval = recorder.approval(9, "Send?", "Send", null, Evidence(), false)
        recorder.resolve(approval, ThreadState.APPROVED)
        recorder.resolve(approval, ThreadState.EXPIRED) // a later, weaker update does not undo it
        assertEquals(ThreadState.APPROVED, recorder.get(approval)!!.state)

        val question = recorder.question("Which?", 9)
        recorder.closeTask(9)
        assertEquals(ThreadState.FAILED, recorder.get(question)!!.state)
        assertEquals(ThreadState.DONE, recorder.get(note)!!.state)

        val progress = recorder.progress("Updating Lingo", "~1 min", "Lingo", 4)
        recorder.progressToResultCard(progress, "Lingo", "Lingo is updated", "tap to open", 4)
        with(recorder.get(progress)!!) {
            assertEquals(ThreadKind.RESULT, kind); assertEquals("Lingo", title); assertEquals(4L, openAppId)
            assertEquals("tap to open", detail)
        }
        val failed = recorder.progress("Building your app", "~1 min", null, null)
        recorder.progressToSentence(failed, "I could not build the app")
        with(recorder.get(failed)!!) { assertEquals(ThreadKind.SENTENCE, kind); assertEquals("I could not build the app.", text) }
    }

    @Test fun atTheChangeOfDayNotificationsWithoutATaskAreDeletedAndTheRestIsKept() = runBlocking {
        recorder.notification("p", "Alex", "private text")
        val linked = recorder.notification("p", "Sam", "photos please")
        recorder.notificationTriaged(linked, 7)
        recorder.owner("yesterday")
        recorder.sentence("Found 12 in your chats.", 7)
        recorder.approval(7, "9 photos to Sam. Send them?", "Send", "p", Evidence(count = 9), true)
        recorder.result(null, "Lingo is ready")
        assertEquals(6, today().size)
        time += DAY
        // The home thread shows only today.
        assertTrue(today().isEmpty())
        recorder.owner("today")
        assertEquals(listOf("today"), today().map { it.text })
        // The notification text that never became a task is gone from storage, not only hidden (F13).
        val stored = db.threadItemDao().sinceNow(0)
        assertTrue(stored.none { it.text == "private text" })
        // The task's rows, the owner's words, and the result stay for the history (F15.5).
        assertEquals(listOf("photos please", "yesterday", "Found 12 in your chats.", "9 photos to Sam. Send them?",
            "Lingo is ready", "today"), stored.map { it.text })
        // Evidence pictures belong to kept tasks: the change of day deletes none.
        assertTrue(pruned.isEmpty())
    }

    @Test fun restartExpiresApprovalsFailsQuestionsAndStopsBuilds() = runBlocking {
        val approval = recorder.approval(1, "Send?", "Send", null, Evidence(), true)
        val question = recorder.question("Which?", 1)
        val build = recorder.progress("Building your app", "~1 min", null, null)
        val waiting = recorder.notification("p", "Alex", "hi")
        val linked = recorder.notification("p", "Sam", "hi").also { recorder.notificationTriaged(it, 5) }
        recorder.expireOpenAfterRestart()
        assertEquals(ThreadState.EXPIRED, recorder.get(approval)!!.state)
        assertEquals(ThreadState.FAILED, recorder.get(question)!!.state)
        assertEquals(ThreadKind.SENTENCE, recorder.get(build)!!.kind)
        assertEquals(ThreadState.DONE, recorder.get(waiting)!!.state)
        assertEquals(ThreadState.OPEN, recorder.get(linked)!!.state) // its task row is still queued
    }

    @Test fun updateOfAMissingRowDoesNothing() = runBlocking {
        recorder.update(99) { it.copy(text = "x") }
        assertNull(recorder.get(99))
    }

    companion object {
        const val DAY = ThreadRecorder.DAY_MS
    }
}
