package ai.opencyclone.feed

import ai.opencyclone.fixtures.eventually
import ai.opencyclone.miniapp.MiniAppEntity
import ai.opencyclone.tasks.TaskEntity
import ai.opencyclone.tasks.WindowEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Lane N5: the live state carries the goals of the waiting tasks and the status emphasis. */
class LiveStateQueuedTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queued = MutableStateFlow(0)
    private var rows = emptyList<TaskEntity>()
    private val source = LiveStateSource(scope, MutableStateFlow<TaskEntity?>(null), queued,
        MutableStateFlow<WindowEntity?>(null), MutableStateFlow<List<MiniAppEntity>>(emptyList()), { rows }) { 1_000L }

    @After fun stop() = scope.cancel()

    private fun t(id: Long, created: Long, goal: String) =
        TaskEntity(id = id, origin = "user", goal = goal, state = "queued", createdAt = created, updatedAt = created)

    @Test fun queuedGoalsFollowTheQueuedRows() = runBlocking {
        rows = listOf(t(9, 20, "Book the table"), t(8, 10, "Reply to Maya"))
        queued.value = 2
        eventually(2000, 20) {
            assertEquals(2, source.state.value.queued)
            assertEquals(listOf("Reply to Maya", "Book the table"), source.state.value.queuedGoals)
        }
        rows = emptyList()
        queued.value = 0
        eventually(2000, 20) { assertEquals(emptyList<String>(), source.state.value.queuedGoals) }
    }

    @Test fun approvalStatusCarriesItsEmphasis() = runBlocking {
        source.setApproval(ApprovalView(4, "Send this to Sam?", "Send to Sam", "com.whatsapp", Evidence(composeText = "Hi"), true),
            StatusSentence.Approval(null, "Sam", "Send", hasText = true))
        eventually(2000, 20) {
            val s = source.state.value
            assertEquals("A message to Sam is ready.", s.status)
            assertEquals("Sam", s.status.substring(s.statusEmphasis!!))
        }
    }
}
