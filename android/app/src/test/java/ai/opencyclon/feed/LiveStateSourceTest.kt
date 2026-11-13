package ai.opencyclon.feed

import ai.opencyclon.action.Action
import ai.opencyclon.engine.AgentState
import ai.opencyclon.engine.EngineEvent
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HoldReason
import ai.opencyclon.gate.HoldResult
import ai.opencyclon.gate.ScreenFacts
import ai.opencyclon.miniapp.MiniAppEntity
import ai.opencyclon.tasks.TaskEntity
import ai.opencyclon.tasks.WindowEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.opencyclon.fixtures.eventually

class LiveStateSourceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var time = 1_000_000L
    private val occupied = MutableStateFlow<TaskEntity?>(null)
    private val queued = MutableStateFlow(0)
    private val window = MutableStateFlow<WindowEntity?>(null)
    private val apps = MutableStateFlow<List<MiniAppEntity>>(emptyList())
    private val source = LiveStateSource(scope, occupied, queued, window, apps) { time }

    @After fun stop() = scope.cancel()

    private fun task(id: Long, state: String = "running") =
        TaskEntity(id = id, origin = "user", goal = "Send Sam the photos", state = state, createdAt = 1, updatedAt = 500)

    private suspend fun expect(block: (LiveState) -> Unit) = eventually(timeoutMs = 2000, intervalMs = 20) { block(source.state.value) }

    @Test fun idleByDefault() = runBlocking {
        expect { assertEquals(LiveState(), it) }
    }

    @Test fun runningTaskCarriesCaptionAppsAndPhase() = runBlocking {
        occupied.value = task(4)
        queued.value = 2
        expect {
            assertEquals(listOf(RunningTask(4, "Send Sam the photos", "Working on it", emptyList(), 500, Phase.RUNNING)), it.running)
            assertEquals(2, it.queued)
            assertEquals("Working on it.", it.status)
        }
        source.onEvent(4, EngineEvent.Started("Send Sam the photos"))
        source.onEvent(4, EngineEvent.Step(1, "com.google.android.apps.photos", "Picking the photos"))
        source.onEvent(4, EngineEvent.Step(2, "com.whatsapp", null))
        source.onEvent(4, EngineEvent.Step(3, "com.google.android.apps.photos", null))
        expect {
            val r = it.running.single()
            assertEquals(listOf("com.whatsapp", "com.google.android.apps.photos"), r.packages)
            assertEquals("Picking the photos", r.caption)
            assertEquals(time, r.startedAt)
            assertEquals("Picking the photos.", it.status)
        }
        source.onEngineState(4, AgentState.Paused)
        expect { assertEquals(Phase.PAUSED, it.running.single().phase) }
        source.onEngineState(4, AgentState.Running(4, "x"))
        source.onEvent(4, EngineEvent.Asking("Which Sam?"))
        expect { assertEquals(Phase.WAITING_ANSWER, it.running.single().phase) }
        occupied.value = task(4, "awaiting_approval")
        expect { assertEquals(Phase.WAITING_APPROVAL, it.running.single().phase) }
        occupied.value = null
        expect { assertTrue(it.running.isEmpty()) }
    }

    @Test fun approvalAndQuestionAndStatusPriority() = runBlocking {
        occupied.value = task(4)
        source.setQuestion(QuestionView(4, "Which Sam?"))
        expect { assertEquals("I have a question.", it.status) }
        val view = ApprovalView(4, "9 photos to Sam. Send them?", "Send to Sam", "com.whatsapp", Evidence(count = 9), true)
        source.setApproval(view, StatusSentence.Approval(9, "Sam", "Send", "photos"))
        expect {
            assertEquals(view, it.approval)
            assertEquals("9 photos are ready to send.", it.status)
        }
        source.clearApproval(99) // another task: no change
        expect { assertEquals(view, it.approval) }
        source.taskEnded(4)
        expect {
            assertNull(it.approval)
            assertNull(it.question)
        }
    }

    @Test fun windowShowsUntilItEnds() = runBlocking {
        time = System.currentTimeMillis()
        window.value = WindowEntity(1, endsAt = time + 300, startedAt = time, handled = 1, hidden = 2, needsYou = 3)
        expect {
            assertEquals(WindowView(time + 300, 1, 2, 3), it.window)
            assertTrue(it.status.startsWith("Handling your notifications until "))
        }
        time += 1_000 // the source's clock passes the end; the delay ends on the real clock
        eventually(timeoutMs = 3000, intervalMs = 50) { assertNull(source.state.value.window) }
        window.value = WindowEntity(2, endsAt = time - 1, startedAt = 0)
        expect { assertNull(it.window); assertEquals("All caught up.", it.status) }
    }

    @Test fun appsHaveAccentAndUpdatingMark() = runBlocking {
        apps.value = listOf(MiniAppEntity(1, "Lingo", 2, 1, accent = "#2F6FDB"), MiniAppEntity(2, "Old", 1, 1))
        source.setUpdating(1, true)
        expect {
            assertEquals(listOf(AppTile(1, "Lingo", "#2F6FDB", updating = true), AppTile(2, "Old", ai.opencyclon.miniapp.BuiltAppStyle.DEFAULT_ACCENT)), it.apps)
        }
        source.setUpdating(1, false)
        expect { assertEquals(false, it.apps.first().updating) }
    }

    @Test fun holdEventsSetThePhase() = runBlocking {
        occupied.value = task(4)
        val held = HeldAction(Action.Tap(1, 2), HoldReason.COMMIT_WORD, "Send", null, 1, 2, ScreenFacts("p", "Sam", null))
        source.onEvent(4, EngineEvent.Held(held))
        expect { assertEquals(Phase.WAITING_APPROVAL, it.running.single().phase) }
        source.onEvent(4, EngineEvent.Resolved(held, HoldResult.Approve))
        expect { assertEquals(Phase.RUNNING, it.running.single().phase) }
    }
}
