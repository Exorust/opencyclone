package ai.opencyclon.feed

import ai.opencyclon.db.AppDatabase
import ai.opencyclon.tasks.TaskEntity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The data behind the history page (F15): status words, apps, search, delete, records, old tasks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val chatDeleted = mutableListOf<String>()
    private val evidence by lazy { tmp.newFolder("evidence") }
    private val history by lazy {
        TaskHistory(db.taskDao(), db.threadItemDao(), evidence) { goal, _, _ -> chatDeleted += goal }
    }

    @After fun close() = db.close()

    private suspend fun task(goal: String, state: String, at: Long, result: String? = null, origin: String = "user"): Long =
        db.taskDao().insert(TaskEntity(origin = origin, goal = goal, state = state, result = result, createdAt = at, updatedAt = at + 1000))

    private suspend fun row(item: ThreadItem): Long = db.threadItemDao().insert(ThreadItemEntity.from(item))

    private fun item(kind: ThreadKind, taskId: Long?, text: String, state: ThreadState = ThreadState.DONE, pkg: String? = null,
                     at: Long = 0, actionWord: String? = null, openAppId: Long? = null) =
        ThreadItem(0, at, kind, state, taskId = taskId, appPackage = pkg, text = text, actionWord = actionWord, openAppId = openAppId)

    private suspend fun entries(query: String = "") = history.history(query).first()

    @Test fun statusWordForEveryStateOfATask() = runBlocking {
        val done = task("done", "done", 1)
        val failed = task("failed", "failed", 2)
        val running = task("running", "running", 3)
        val paused = task("paused", "paused", 4)
        val approval = task("awaiting", "awaiting_approval", 5)
        val asking = task("asking", "running", 6)
        row(item(ThreadKind.QUESTION, asking, "Which album?", ThreadState.OPEN))
        val notNow = task("not now", "cancelled", 7)
        row(item(ThreadKind.APPROVAL, notNow, "Send it?", ThreadState.REJECTED))
        row(item(ThreadKind.SENTENCE, notNow, FeedText.STOPPED))
        val stopped = task("stopped", "cancelled", 8)
        row(item(ThreadKind.SENTENCE, stopped, FeedText.STOPPED_AFTER_SENDING))
        val expired = task("expired", "cancelled", 9)
        row(item(ThreadKind.APPROVAL, expired, "Send it?", ThreadState.EXPIRED))
        row(item(ThreadKind.SENTENCE, expired, FeedText.EXPIRED))
        val declined = task("declined", "cancelled", 10, origin = "notification")
        val proposed = task("proposed", "proposed", 11, origin = "notification")

        val byId = entries().associate { it.taskId to it.status }
        assertEquals(HistoryStatus.DONE, byId[done])
        assertEquals(HistoryStatus.FAILED, byId[failed])
        assertEquals(HistoryStatus.RUNNING, byId[running])
        assertEquals(HistoryStatus.RUNNING, byId[paused])
        assertEquals(HistoryStatus.WAITING, byId[approval])
        assertEquals(HistoryStatus.WAITING, byId[asking])
        assertEquals(HistoryStatus.NOT_SENT, byId[notNow])
        assertEquals(HistoryStatus.STOPPED, byId[stopped])
        assertEquals(HistoryStatus.FAILED, byId[expired])
        assertEquals(HistoryStatus.NOT_SENT, byId[declined])
        assertEquals(HistoryStatus.WAITING, byId[proposed])
    }

    @Test fun newestFirstOneLineTitleAndAtMostThreeAppsFromApprovalsReceiptsAndNotifications() = runBlocking {
        val old = task("Old task", "done", 100)
        val id = task("Follow Jordan.\nReply to Nina.", "done", 200)
        row(item(ThreadKind.NOTIFICATION, id, "hi", pkg = "a"))
        row(item(ThreadKind.SENTENCE, id, "Working.", pkg = "z"))
        row(item(ThreadKind.APPROVAL, id, "Send?", ThreadState.APPROVED, pkg = "b"))
        row(item(ThreadKind.RECEIPT, id, "x", pkg = "b"))
        row(item(ThreadKind.RECEIPT, id, "y", pkg = "c"))
        row(item(ThreadKind.RECEIPT, id, "w", pkg = "d"))
        val list = entries()
        assertEquals(listOf(id, old), list.map { it.taskId })
        assertEquals("Follow Jordan. Reply to Nina.", list[0].title)
        assertEquals(listOf("a", "b", "c"), list[0].packages)
        assertEquals(200L, list[0].at)
    }

    @Test fun searchMatchesTitleResultAndReceiptsInAnyCaseWithTheMatchRange() = runBlocking {
        val byTitle = task("Message Alex about Saturday", "done", 1)
        val byResult = task("Book the table", "done", 2, result = "Booked for ALEX and 3 others.")
        val byReceipt = task("Send the photos", "done", 3)
        row(item(ThreadKind.RECEIPT, byReceipt, "9 photos to Alex", actionWord = "Sent"))
        task("Something else", "done", 4)
        val found = entries("alex")
        assertEquals(setOf(byTitle, byResult, byReceipt), found.map { it.taskId }.toSet())
        assertEquals(8..11, found.first { it.taskId == byTitle }.match)
        assertNull(found.first { it.taskId == byResult }.match)
        assertTrue(entries("zzqx").isEmpty())
        assertEquals(4, entries("").size)
    }

    @Test fun deleteRemovesTheTaskItsRowsItsEvidenceAndItsChatLogButNotARunningTask() = runBlocking {
        val id = task("Send Alex the file", "done", 1)
        row(item(ThreadKind.APPROVAL, id, "Send?", ThreadState.APPROVED))
        row(item(ThreadKind.OWNER, null, "Keep me"))
        File(evidence, id.toString()).apply { mkdirs(); File(this, "frame.jpg").writeText("x") }
        val running = task("Running one", "running", 2)

        assertTrue(history.delete(id))
        assertNull(db.taskDao().get(id))
        assertTrue(db.threadItemDao().allNow().none { it.taskId == id })
        assertFalse(File(evidence, id.toString()).exists())
        assertEquals(listOf("Send Alex the file"), chatDeleted)
        assertEquals(listOf("Keep me"), db.threadItemDao().allNow().map { it.text })

        assertFalse(history.delete(running))
        assertEquals("running", db.taskDao().get(running)?.state)
    }

    @Test fun recordIsTheOwnersWordsThenTheTasksRows() = runBlocking {
        val id = task("Open Gate Test and tap Send", "done", 1)
        row(item(ThreadKind.SENTENCE, id, "Found the chat.", at = 2))
        row(item(ThreadKind.APPROVAL, id, "Send it?", ThreadState.APPROVED, at = 3))
        row(item(ThreadKind.RECEIPT, id, "to Alex", at = 4, actionWord = "Sent"))
        val record = history.record(id).first()
        assertEquals(listOf(ThreadKind.OWNER, ThreadKind.SENTENCE, ThreadKind.APPROVAL, ThreadKind.RECEIPT), record.map { it.kind })
        assertEquals("Open Gate Test and tap Send", record[0].text)
    }

    @Test fun aTaskFromBeforeVersionTwoShowsItsGoalAndItsResult() = runBlocking {
        val id = task("Check the weather", "done", 1, result = "It is sunny. Highs of 20.")
        val entry = entries().single()
        assertEquals("Check the weather", entry.title)
        assertEquals(HistoryStatus.DONE, entry.status)
        assertTrue(entry.packages.isEmpty())
        val record = history.record(id).first()
        assertEquals(listOf(ThreadKind.OWNER, ThreadKind.SENTENCE), record.map { it.kind })
        assertEquals(listOf("Check the weather", "It is sunny."), record.map { it.text })
    }

    @Test fun aBuiltAppIsListedByItsRequestAndItsResult() = runBlocking {
        row(item(ThreadKind.OWNER, null, "Make me a habit tracker", at = 10))
        val result = row(item(ThreadKind.RESULT, null, "Habits is ready", at = 20, openAppId = 4))
        val entry = entries().single()
        assertEquals(-result, entry.taskId)
        assertEquals("Make me a habit tracker", entry.title)
        assertEquals(HistoryStatus.DONE, entry.status)
        assertEquals("Make me a habit tracker", history.title(-result))
        assertEquals(listOf("Make me a habit tracker", "Habits is ready"), history.record(-result).first().map { it.text })
        assertTrue(history.delete(-result))
        assertTrue(db.threadItemDao().allNow().isEmpty())
    }
}
