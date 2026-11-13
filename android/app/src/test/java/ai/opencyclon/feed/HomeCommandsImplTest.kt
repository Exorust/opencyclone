package ai.opencyclon.feed

import ai.opencyclon.action.Action
import ai.opencyclon.gate.ApprovalSession
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HoldReason
import ai.opencyclon.gate.HoldResult
import ai.opencyclon.gate.ScreenFacts
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCommandsImplTest {
    private val log = mutableListOf<String>()
    private var builderWaiting = false
    private var engineWaiting = false
    private var unlocked = true
    private val session = ApprovalSession()

    private val commands = HomeCommandsImpl(
        builderQuestionWaiting = { builderWaiting },
        answerBuilderQuestion = { log += "builder:$it"; true },
        engineQuestionWaiting = { engineWaiting },
        answerEngine = { log += "engine:$it" },
        session = { session },
        approveByYes = { text ->
            val pending = session.pending()
            if (HomeCommandsImpl.isPlainYes(text) && unlocked && pending != null && pending.held.facts.title != null) {
                session.approve(pending.taskId)
            } else false
        },
        isYes = HomeCommandsImpl::isPlainYes,
        newRequest = { text, recorded -> log += "new:$text:$recorded" },
        recordOwner = { log += "owner:$it" },
        say = { log += "say:$it" },
        isUnlocked = { unlocked },
        cancelTask = { log += "cancel:$it" },
        openLiveView = { _, id -> log += "live:$id" },
    )

    private fun held(title: String? = "Alex") =
        HeldAction(Action.Tap(1, 2), HoldReason.COMMIT_WORD, "Send", null, 1, 2, ScreenFacts("p", title, null))

    @Test fun builderQuestionComesFirst() {
        builderWaiting = true
        engineWaiting = true
        commands.submit(" streaks please ")
        assertEquals(listOf("owner:streaks please", "builder:streaks please"), log)
    }

    @Test fun thenTheEnginesQuestion() {
        engineWaiting = true
        commands.submit("The one from work")
        assertEquals(listOf("owner:The one from work", "engine:The one from work"), log)
    }

    @Test fun wordsWhileAnApprovalWaitsEditIt() = runBlocking {
        val result = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(5, held()) }
        commands.submit("Only the first three photos")
        assertEquals(HoldResult.Edit("Only the first three photos"), result.await())
        assertEquals(listOf("owner:Only the first three photos"), log)
    }

    @Test fun aPlainYesApproves() = runBlocking {
        for (yes in listOf("yes", "Send it.", "do it")) {
            val result = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(5, held()) }
            commands.submit(yes)
            assertEquals(yes, HoldResult.Approve, result.await())
        }
    }

    @Test fun yesOnALockedPhoneDoesNotApproveOrEdit() = runBlocking {
        unlocked = false
        val result = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(5, held()) }
        commands.submit("yes")
        yield()
        assertTrue(result.isActive)
        assertEquals(listOf("owner:yes", "say:${HomeCommandsImpl.UNLOCK_TO_APPROVE}"), log)
        session.reject(5)
        Unit
    }

    @Test fun otherwiseANewRequest() {
        commands.submit("Send Alex the photos")
        commands.submit("   ")
        assertEquals(listOf("new:Send Alex the photos:false"), log)
    }

    @Test fun approveIsRefusedWhenLockedOrGoneOrUnreadable() = runBlocking {
        assertFalse(commands.approve(5)) // nothing waits
        val result = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(5, held()) }
        unlocked = false
        assertFalse(commands.approve(5))
        unlocked = true
        assertFalse(commands.approve(6)) // another task
        assertTrue(result.isActive)
        assertTrue(commands.approve(5))
        assertEquals(HoldResult.Approve, result.await())

        val unreadable = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(8, held(title = null)) }
        assertFalse(commands.approve(8))
        commands.reject(8)
        assertEquals(HoldResult.Reject, unreadable.await())
    }

    @Test fun stopAndOpenLive() {
        commands.stop(3)
        commands.openLive(mockk<Context>(relaxed = true), 3)
        assertEquals(listOf("cancel:3", "live:3"), log)
    }
}
