package ai.opencyclone.tasks

import ai.opencyclone.db.AppDatabase
import ai.opencyclone.proactive.AllowlistEntity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskCoordinatorTest {
    private lateinit var db: AppDatabase
    private lateinit var coordinator: TaskCoordinator
    private var time = 1_000L
    private val starts = mutableListOf<Long>()
    private val stops = mutableListOf<Long>()
    private val shown = mutableListOf<Long>()
    private val dismissed = mutableListOf<Long>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        coordinator = TaskCoordinator(db.taskDao(), db.windowDao(), db.allowlistDao(), object : TaskRunner {
            override fun start(taskId: Long, goal: String) { starts.add(taskId) }
            override fun stop(taskId: Long) { stops.add(taskId) }
        }, object : ProposalPresenter {
            override fun showDoThis(taskId: Long, goal: String, sourceLabel: String) { shown.add(taskId) }
            override fun dismiss(taskId: Long) { dismissed.add(taskId) }
        }, { time })
    }
    @After fun close() { db.close() }
    private suspend fun propose(key: String = "chat", goal: String = "reply") = coordinator.submitProposal(goal, "chat.app", key, "Alex")!!

    @Test fun singleSlotAndOwnerPriority() = runBlocking {
        coordinator.startWindow(60)
        val running = coordinator.submitUserRequest("first")
        val notification = propose()
        val user = coordinator.submitUserRequest("owner")
        assertEquals(listOf(running), starts)
        coordinator.onTaskFinished(running, "done", null)
        assertEquals(listOf(running, user), starts)
        coordinator.onTaskFinished(user, "done", null)
        assertEquals(listOf(running, user, notification), starts)
    }

    @Test fun simultaneousRequestsHaveOneSlot() = runBlocking {
        coroutineScope { (1..20).map { async(Dispatchers.Default) { coordinator.submitUserRequest("task $it") } }.awaitAll() }
        assertEquals(1, starts.size)
        assertEquals(19, db.taskDao().all().count { it.state == "queued" })
    }

    @Test fun proposedAndQueuedDeduplicateAndCanRecurAfterRunning() = runBlocking {
        val id = propose()
        assertEquals(id, propose(goal = "updated"))
        assertEquals("updated", db.taskDao().get(id)!!.goal)
        assertEquals(1, db.taskDao().all().size)
        val busy = coordinator.submitUserRequest("busy")
        coordinator.acceptProposal(id)
        assertEquals(id, propose(goal = "new goal"))
        assertEquals(2, db.taskDao().all().size)
        coordinator.onTaskFinished(busy, "done", null)
        assertNull(db.taskDao().get(id)!!.sourceKey)
        assertNotEquals(id, propose())
    }

    @Test fun activeWindowRunsWithoutProposalCard() = runBlocking {
        coordinator.startWindow(60)
        val id = propose()
        assertTrue(shown.isEmpty())
        assertEquals(listOf(id), starts)
    }

    @Test fun allowlistRunsWithoutProposalCard() = runBlocking {
        db.allowlistDao().insert(AllowlistEntity("chat.app"))
        assertEquals(listOf(propose()), starts)
        assertTrue(shown.isEmpty())
    }

    @Test fun neitherPermissionShowsCardAndAcceptRuns() = runBlocking {
        val id = propose()
        assertEquals(listOf(id), shown)
        assertTrue(starts.isEmpty())
        coordinator.acceptProposal(id)
        assertEquals(listOf(id), starts)
        assertTrue(id in dismissed)
    }

    @Test fun proposedExpiresAtThirtyMinutesDespiteUpdate() = runBlocking {
        val id = propose()
        time += 29 * 60_000
        propose(goal = "update")
        coordinator.expireOld()
        assertEquals("proposed", db.taskDao().get(id)!!.state)
        time += 60_000
        coordinator.expireOld()
        assertEquals("cancelled", db.taskDao().get(id)!!.state)
        assertTrue(id in dismissed)
    }

    @Test fun queuedNotificationExpiresBeforeSlotStartsIt() = runBlocking {
        coordinator.startWindow(60)
        val busy = coordinator.submitUserRequest("busy")
        val queued = propose()
        val owner = coordinator.submitUserRequest("owner")
        time += TaskCoordinator.EXPIRY_MS
        coordinator.onTaskFinished(busy, "done", null)
        assertEquals("cancelled", db.taskDao().get(queued)!!.state)
        assertEquals(listOf(busy, owner), starts)
        assertTrue(queued in dismissed)
    }

    @Test fun approvalExpiryUsesHeldTimeAndStopsRunner() = runBlocking {
        val id = coordinator.submitUserRequest("send")
        time += TaskCoordinator.EXPIRY_MS
        coordinator.onAwaitingApproval(id, "action", "facts")
        coordinator.submitUserRequest("next")
        coordinator.expireOld()
        assertEquals("awaiting_approval", db.taskDao().get(id)!!.state)
        assertEquals(1, starts.size)
        time += TaskCoordinator.EXPIRY_MS
        coordinator.expireOld()
        assertEquals("cancelled", db.taskDao().get(id)!!.state)
        assertEquals(listOf(id), stops)
        assertEquals(2, starts.size)
    }

    @Test fun declineEndsAProposalForGoodAndTheSameSenderProposesAgain() = runBlocking {
        val first = propose()
        assertTrue(coordinator.declineProposal(first))
        val row = db.taskDao().get(first)!!
        assertEquals("cancelled", row.state)
        assertNull(row.sourceKey)
        assertTrue(first in dismissed)
        assertFalse(coordinator.declineProposal(first)) // once only
        val second = propose()
        assertNotEquals(first, second)
        assertEquals("proposed", db.taskDao().get(second)!!.state)
        assertTrue(starts.isEmpty())
    }

    @Test fun declineDoesNotTouchARunningTask() = runBlocking {
        val id = coordinator.submitUserRequest("run")
        assertFalse(coordinator.declineProposal(id))
        assertEquals("running", db.taskDao().get(id)!!.state)
    }

    @Test fun windowCountsAreIdempotentAndStopDisablesAutomaticStart() = runBlocking {
        coordinator.startWindow(60)
        val done = propose("one")
        coordinator.onTaskFinished(done, "done", "ok")
        coordinator.onTaskFinished(done, "done", "ok")
        val failed = propose("two")
        coordinator.onTaskFinished(failed, "failed", "missing")
        val cancelled = propose("three")
        coordinator.onTaskFinished(cancelled, "cancelled", null)
        db.windowDao().count(time, hidden = 2)
        assertEquals(WindowSummary(1, 2, 2), coordinator.stopWindow())
        propose("four")
        assertEquals(1, shown.size)
        assertEquals(WindowSummary(1, 2, 2), coordinator.stopWindow())
    }

    @Test fun windowBoundaryAndReplacement() = runBlocking {
        coordinator.startWindow(1)
        coordinator.startWindow(2)
        db.windowDao().count(time, hidden = 1)
        time += 120_000
        propose()
        assertEquals(1, shown.size)
        assertEquals(WindowSummary(0, 1, 0), coordinator.stopWindow())
    }

    @Test fun approvedTaskIsNotExpiredAfterThirtyMinutes() = runBlocking {
        val id = coordinator.submitUserRequest("send photos")
        coordinator.onAwaitingApproval(id, "{}", "{}")
        time += 60_000L // approved at minute 1
        coordinator.onApprovalResolved(id)
        val task = db.taskDao().get(id)!!
        assertEquals("running", task.state)
        assertNull(task.heldAction)
        time += 30 * 60_000L // minute 31
        coordinator.expireOld()
        assertEquals("running", db.taskDao().get(id)!!.state)
        assertTrue(stops.isEmpty())
    }

    @Test fun secondHoldOnTheSameTaskIsRecorded() = runBlocking {
        val id = coordinator.submitUserRequest("send then call")
        coordinator.onAwaitingApproval(id, "{\"n\":1}", "{}")
        coordinator.onApprovalResolved(id)
        coordinator.onAwaitingApproval(id, "{\"n\":2}", "{}")
        val task = db.taskDao().get(id)!!
        assertEquals("awaiting_approval", task.state)
        assertEquals("{\"n\":2}", task.heldAction)
    }

    @Test fun approvalResolvedIgnoresTasksThatAreNotHeld() = runBlocking {
        val id = coordinator.submitUserRequest("first")
        coordinator.onTaskFinished(id, "cancelled", null)
        coordinator.onApprovalResolved(id)
        assertEquals("cancelled", db.taskDao().get(id)!!.state)
    }

    // ── Restart recovery after a process stop that was not a reboot ─────

    private fun recovering() = TaskCoordinator(db.taskDao(), db.windowDao(), db.allowlistDao(), object : TaskRunner {
        override fun start(taskId: Long, goal: String) { starts.add(taskId) }
        override fun stop(taskId: Long) { stops.add(taskId) }
    }, object : ProposalPresenter {
        override fun showDoThis(taskId: Long, goal: String, sourceLabel: String) { shown.add(taskId) }
        override fun dismiss(taskId: Long) { dismissed.add(taskId) }
    }, { time }, recoverOnFirstUse = true)

    @Test fun staleSlotStatesAreClosedAtProcessStartAndANewRequestRuns() = runBlocking {
        for (state in listOf("running", "paused", "awaiting_approval")) {
            val stale = db.taskDao().insert(TaskEntity(origin = "user", goal = state, state = state,
                heldAction = "{}", createdAt = time, updatedAt = time))
            starts.clear()
            val fresh = recovering()
            assertEquals(state, listOf(stale), fresh.recoverAfterProcessStart())
            val closed = db.taskDao().get(stale)!!
            assertEquals(state, "failed", closed.state)
            assertEquals(TaskCoordinator.STOPPED_RESULT, closed.result)
            assertNull(closed.heldAction)
            val next = fresh.submitUserRequest("after $state")
            assertEquals(state, listOf(next), starts)
            fresh.onTaskFinished(next, "done", null)
        }
        assertTrue(stops.isEmpty())
    }

    @Test fun recoveryRunsBeforeTheFirstRequestEvenIfTheRequestWinsTheRace() = runBlocking {
        val stale = db.taskDao().insert(TaskEntity(origin = "user", goal = "old", state = "running", createdAt = time, updatedAt = time))
        val fresh = recovering()
        val next = fresh.submitUserRequest("new")
        assertEquals(listOf(next), starts)
        assertEquals("failed", db.taskDao().get(stale)!!.state)
        // The explicit call still reports the closed row once the request ran, and closes nothing new.
        assertEquals(listOf(stale), fresh.recoverAfterProcessStart())
        assertEquals("running", db.taskDao().get(next)!!.state)
    }

    @Test fun recoveryKeepsQueuedProposedAndFinishedRows() = runBlocking {
        listOf("proposed", "queued", "done", "failed", "cancelled").forEach {
            db.taskDao().insert(TaskEntity(origin = "user", goal = it, state = it, createdAt = time, updatedAt = time))
        }
        assertTrue(recovering().recoverAfterProcessStart().isEmpty())
        assertEquals(listOf("proposed", "queued", "done", "failed", "cancelled"), db.taskDao().all().map { it.state })
        assertTrue(starts.isEmpty())
    }

    @Test fun coordinatorWithoutRecoveryLeavesRowsAlone() = runBlocking {
        db.taskDao().insert(TaskEntity(origin = "user", goal = "old", state = "running", createdAt = time, updatedAt = time))
        assertTrue(coordinator.recoverAfterProcessStart().isEmpty())
        coordinator.submitUserRequest("new")
        assertTrue(starts.isEmpty())
    }
}
