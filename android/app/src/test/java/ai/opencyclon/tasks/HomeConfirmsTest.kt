package ai.opencyclon.tasks

import ai.opencyclon.db.AppDatabase
import ai.opencyclon.feed.ConfirmRef
import ai.opencyclon.feed.HomeCommandsImpl
import ai.opencyclon.feed.LiveStateSource
import ai.opencyclon.feed.TaskEventSink
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.feed.ThreadRecorder
import ai.opencyclon.feed.ThreadState
import ai.opencyclon.gate.ApprovalSession
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lane X2 item 1 and 4: the "Do this?" row of a proposal from both surfaces, and the "Set up" and "Allow" rows.
 * The coordinator, the recorder and the task-end sink are the real ones; the runtime's glue (the presenter that
 * also writes the row, the end of a task that closes its rows) is rebuilt here in a few lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeConfirmsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var time = 1_000_000L
    private val recorder = ThreadRecorder(db.threadItemDao(), { time })
    private val live = LiveStateSource(scope, MutableStateFlow(null), MutableStateFlow(0), MutableStateFlow(null), MutableStateFlow(emptyList()))
    private val sink = TaskEventSink(recorder, live) { null }
    private val starts = mutableListOf<Long>()
    private val cards = mutableListOf<Long>()          // proposal notifications shown
    private val removed = mutableListOf<Long>()        // proposal notifications removed
    private val ended = mutableListOf<Triple<TaskEntity, String, String?>>()
    private val said = mutableListOf<String>()
    private var unlocked = true
    private var setUpOpened = 0
    private var micOpened = 0
    private val widgetAnswers = mutableListOf<Pair<Long, Boolean>>()
    private val shown = mutableListOf<Pair<Long, String>>()

    private val coordinator = TaskCoordinator(db.taskDao(), db.windowDao(), db.allowlistDao(), object : TaskRunner {
        override fun start(taskId: Long, goal: String) { starts += taskId }
        override fun stop(taskId: Long) = Unit
    }, object : ProposalPresenter {
        override fun showDoThis(taskId: Long, goal: String, sourceLabel: String) { cards += taskId; shown += taskId to goal }
        override fun dismiss(taskId: Long) { removed += taskId }
    }, { time }, onFinished = { before, state, result -> ended += Triple(before, state, result) })

    private val confirms = HomeConfirms(recorder, coordinator, { unlocked }, { said += it }, { setUpOpened++ }, { micOpened++ },
        { id, yes -> widgetAnswers += id to yes })

    @After fun close() {
        scope.cancel()
        db.close()
    }

    /** What the runtime does after each coordinator call: the presenter's rows, then the ends of tasks. */
    private suspend fun flush() {
        shown.toList().also { shown.clear() }.forEach { (id, goal) -> confirms.proposalShown(id, goal) }
        ended.toList().also { ended.clear() }.forEach { (before, state, result) ->
            sink.onTaskEnded(before.id, before.state, state, result, expired = false)
        }
    }

    private suspend fun rows() = recorder.today().first()
    private suspend fun row(id: Long) = rows().single { it.id == id }
    private suspend fun confirmRows() = rows().filter { it.kind == ThreadKind.CONFIRM }

    /** A message from Alex that triage proposes: the notification card, linked to the proposal, then its row. */
    private suspend fun alexAsks(goal: String = "Reply to Alex that dinner at 7 works"): Pair<Long, Long> {
        val card = recorder.notification("com.whatsapp", "Alex", "Is dinner at 7 ok?")
        val task = coordinator.submitProposal(goal, "com.whatsapp", "whatsapp|alex", "Alex")!!
        recorder.notificationTriaged(card, task)
        flush()
        return card to task
    }

    @Test fun aProposalWritesADoThisRowUnderItsNotificationCard() = runBlocking {
        val (card, task) = alexAsks()
        val list = rows()
        val confirm = list.single { it.kind == ThreadKind.CONFIRM }
        assertTrue(list.indexOfFirst { it.id == card } < list.indexOf(confirm))
        assertEquals("Reply to Alex that dinner at 7 works?", confirm.text)
        assertEquals("Do it", confirm.actionWord)
        assertEquals(ConfirmRef.PROPOSAL, confirm.title)
        assertEquals(task, confirm.taskId)
        assertEquals(ThreadState.OPEN, confirm.state)
        assertEquals("proposed", db.taskDao().get(task)!!.state)
        assertTrue(starts.isEmpty())
    }

    @Test fun theSameSenderAgainUpdatesTheRowAndAddsNoSecondOne() = runBlocking {
        val (_, task) = alexAsks()
        coordinator.submitProposal("Reply to Alex that 8 works better", "com.whatsapp", "whatsapp|alex", "Alex")
        flush()
        val only = confirmRows().single()
        assertEquals(task, only.taskId)
        assertEquals("Reply to Alex that 8 works better?", only.text)
    }

    @Test fun theSameGoalAgainWhileOpenRepostsTheSameProposal() = runBlocking {
        val (_, task) = alexAsks()
        val (_, again) = alexAsks()
        assertEquals(task, again)
        assertEquals(listOf(task, task), cards)                 // the card is posted again
        assertEquals(1, confirmRows().size)
        assertEquals(1, db.taskDao().all().count { it.state == "proposed" })
    }

    @Test fun afterAnAcceptedProposalTheSameMessageMakesANewOne() = runBlocking {
        val (_, task) = alexAsks()
        confirms.accept(task)
        flush()
        val (_, next) = alexAsks()
        assertNotEquals(task, next)
        assertEquals("proposed", db.taskDao().get(next)!!.state)
        assertEquals(listOf(ThreadState.ANSWERED, ThreadState.OPEN), confirmRows().map { it.state })
    }

    @Test fun afterAnExpiredProposalTheSameMessageMakesANewOne() = runBlocking {
        val (_, task) = alexAsks()
        time += TaskCoordinator.EXPIRY_MS
        coordinator.expireOld()
        flush()
        val (_, next) = alexAsks()
        assertNotEquals(task, next)
        assertEquals("proposed", db.taskDao().get(next)!!.state)
        assertEquals(listOf(ThreadState.EXPIRED, ThreadState.OPEN), confirmRows().map { it.state })
    }

    @Test fun doItOnTheHomePageStartsTheTaskAndRemovesTheNotification() = runBlocking {
        val (_, task) = alexAsks()
        confirms.confirm(confirmRows().single().id, true)
        flush()
        assertEquals(listOf(task), starts)
        assertEquals("running", db.taskDao().get(task)!!.state)
        assertTrue(task in removed)
        assertEquals(ThreadState.ANSWERED, confirmRows().single().state)
    }

    @Test fun doItNeedsAnUnlockedPhoneLikeTheNotification() = runBlocking {
        val (_, task) = alexAsks()
        unlocked = false
        confirms.confirm(confirmRows().single().id, true)
        assertTrue(starts.isEmpty())
        assertEquals("proposed", db.taskDao().get(task)!!.state)
        assertEquals(ThreadState.OPEN, confirmRows().single().state)
        assertEquals(listOf(HomeConfirms.UNLOCK_TO_START), said)
    }

    @Test fun doThisOnTheNotificationAnswersTheHomePageRow() = runBlocking {
        val (_, task) = alexAsks()
        confirms.accept(task) // ProposalReceiver ACTION_ACCEPT
        flush()
        assertEquals(listOf(task), starts)
        assertEquals(ThreadState.ANSWERED, confirmRows().single().state)
    }

    @Test fun notNowEndsTheProposalForGoodAndTheNextMessageMakesANewOne() = runBlocking {
        val (card, task) = alexAsks()
        confirms.confirm(confirmRows().single().id, false)
        flush()
        assertEquals("cancelled", db.taskDao().get(task)!!.state)
        assertTrue(task in removed)                                     // the notification is gone
        assertEquals(ThreadState.REJECTED, confirmRows().single().state)
        assertEquals(ThreadState.DONE, row(card).state)                 // the incoming card is done
        assertTrue(starts.isEmpty())
        // The same sender writes again: a new proposal and a new row, never the old one again.
        val (_, second) = alexAsks("Reply to Alex that 8 works")
        assertNotEquals(task, second)
        assertEquals(listOf(ThreadState.REJECTED, ThreadState.OPEN), confirmRows().map { it.state })
        assertEquals(second, confirmRows().last().taskId)
    }

    @Test fun dismissOnTheNotificationIsTheSameAsNotNow() = runBlocking {
        val (card, task) = alexAsks()
        confirms.decline(task) // ProposalReceiver ACTION_DISMISS
        flush()
        assertEquals("cancelled", db.taskDao().get(task)!!.state)
        assertEquals(ThreadState.REJECTED, confirmRows().single().state)
        assertEquals(ThreadState.DONE, row(card).state)
        // A late "Do it" on the closed row does nothing.
        confirms.confirm(confirmRows().single().id, true)
        assertTrue(starts.isEmpty())
    }

    @Test fun anExpiredProposalMarksItsRowExpired() = runBlocking {
        val (_, task) = alexAsks()
        time += TaskCoordinator.EXPIRY_MS
        coordinator.expireOld()
        flush()
        assertEquals("cancelled", db.taskDao().get(task)!!.state)
        assertEquals(ThreadState.EXPIRED, confirmRows().single().state)
    }

    @Test fun aRestartKeepsTheProposalRowOpenAndExpiresAWidgetQuestion() = runBlocking {
        alexAsks()
        val widget = recorder.confirm("This widget will read: your task history. OK?", "Build it")
        recorder.expireOpenAfterRestart()
        assertEquals(ThreadState.OPEN, confirmRows().first { it.title == ConfirmRef.PROPOSAL }.state)
        assertEquals(ThreadState.EXPIRED, row(widget).state)
    }

    @Test fun theDispatchTellsAWidgetConfirmFromAProposalByTheStoredReference() = runBlocking {
        val (_, task) = alexAsks()
        // Same words as a proposal, but no reference: the widget flow gets it.
        val widget = recorder.confirm("Reply to Alex that dinner at 7 works?", "Do it")
        val draftCard = recorder.result(null, "Your widget draft is ready")
        confirms.confirm(widget, true)
        confirms.confirm(draftCard, true)
        assertEquals(listOf(widget to true, draftCard to true), widgetAnswers)
        assertTrue(starts.isEmpty())
        assertEquals("proposed", db.taskDao().get(task)!!.state)
    }

    @Test fun noModelWritesTheSentenceAndASetUpRow() = runBlocking {
        confirms.noModel()
        val list = rows()
        assertEquals(listOf(ThreadKind.SENTENCE, ThreadKind.CONFIRM), list.map { it.kind })
        assertEquals("No model is set, so I did nothing.", list[0].text)
        assertEquals("Set up", list[1].actionWord)
        assertEquals(ConfirmRef.SET_UP, list[1].title)
        confirms.confirm(list[1].id, true)
        assertEquals(1, setUpOpened)
        assertEquals(ThreadState.ANSWERED, row(list[1].id).state)
        assertTrue(db.taskDao().all().isEmpty())
    }

    @Test fun microphoneOffWritesTheSentenceAndAnAllowRow() = runBlocking {
        confirms.microphoneOff()
        val list = rows()
        assertEquals("The microphone is off, so I can't listen.", list[0].text)
        assertEquals("Allow", list[1].actionWord)
        confirms.confirm(list[1].id, true)
        assertEquals(1, micOpened)
        confirms.microphoneOff()
        confirms.confirm(rows().last().id, false)
        assertEquals(1, micOpened)
        assertEquals(ThreadState.REJECTED, rows().last().state)
    }

    @Test fun theQuestionKeepsTheGoalsWordsAndEndsWithAQuestionMark() {
        assertEquals("Reply to Alex that dinner at 7 works?", HomeConfirms.question("Reply to Alex that dinner at 7 works."))
        assertEquals("Is it raining?", HomeConfirms.question("Is it raining?"))
        assertEquals("Do this?", HomeConfirms.question("  "))
    }

    @Test fun homeCommandsNoteAndMicrophoneOffReachTheirHandlers() {
        val notes = mutableListOf<String>()
        var mic = 0
        val commands = HomeCommandsImpl(
            builderQuestionWaiting = { false }, answerBuilderQuestion = { false }, engineQuestionWaiting = { false },
            answerEngine = {}, session = { ApprovalSession() }, approveByYes = { false }, isYes = { false },
            newRequest = { _, _ -> }, recordOwner = {}, say = { notes += "say:$it" }, isUnlocked = { true },
            cancelTask = {}, openLiveView = { _, _ -> }, onNote = { notes += it }, onMicrophoneOff = { mic++ })
        commands.note(" I did not hear anything. ")
        commands.note("   ")
        commands.microphoneOff()
        assertEquals(listOf("I did not hear anything."), notes)
        assertEquals(1, mic)
    }

    @Test fun theModelSwitchSentenceIsWrittenOnceWhenATaskEnds() = runBlocking {
        var notice: String? = "I switched to gpt-5.6-terra, because this account cannot use gpt-6."
        val withNotice = TaskEventSink(recorder, live, modelSwitchNotice = { notice.also { notice = null } }) { null }
        withNotice.onTaskEnded(1, "running", "done", "Opened Clock.", expired = false)
        withNotice.onTaskEnded(2, "running", "done", "Opened Clock.", expired = false)
        val sentences = rows().filter { it.kind == ThreadKind.SENTENCE }.map { it.text }
        assertEquals(listOf("I switched to gpt-5.6-terra, because this account cannot use gpt-6."), sentences)
        assertNull(notice)
    }
}
