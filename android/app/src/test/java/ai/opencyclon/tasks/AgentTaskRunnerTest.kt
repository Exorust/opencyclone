package ai.opencyclon.tasks

import ai.opencyclon.App
import ai.opencyclon.db.AppDatabase
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentTaskRunnerTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val failures = mutableListOf<Pair<Long, String>>()
    private val started = mutableListOf<Intent>()

    private inner class FakeContext(private val error: RuntimeException?) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent): ComponentName? {
            error?.let { throw it }
            started.add(service)
            return service.component
        }
    }

    @Before fun noService() { App.agentService = null }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun `background start refusal is reported as a failure instead of crashing`() {
        val runner = AgentTaskRunner(FakeContext(ForegroundServiceStartNotAllowedException("not allowed"))) { id, reason ->
            failures.add(id to reason)
        }
        runner.start(7, "reply to Alex")
        idle() // would rethrow if the exception escaped the posted block
        assertEquals(listOf(7L to AgentTaskRunner.BACKGROUND_START_FAILED), failures)
        assertEquals("I could not start in the background. Open the app once.", AgentTaskRunner.BACKGROUND_START_FAILED)
    }

    @Test fun `security exception is reported too`() {
        val runner = AgentTaskRunner(FakeContext(SecurityException("denied"))) { id, reason -> failures.add(id to reason) }
        runner.start(3, "goal")
        idle()
        assertEquals(listOf(3L to AgentTaskRunner.BACKGROUND_START_FAILED), failures)
    }

    @Test fun `successful start sends the task to the service`() {
        val runner = AgentTaskRunner(FakeContext(null)) { id, reason -> failures.add(id to reason) }
        runner.start(5, "goal")
        idle()
        assertTrue(failures.isEmpty())
        val intent = started.single()
        assertEquals(AgentTaskRunner.ACTION_RUN_TASK, intent.action)
        assertEquals(5L, intent.getLongExtra(AgentTaskRunner.EXTRA_TASK_ID, -1))
        assertEquals("goal", intent.getStringExtra(AgentTaskRunner.EXTRA_GOAL))
    }

    @Test fun `a refused start frees the slot so the next request runs`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            var refuse = true
            lateinit var coordinator: TaskCoordinator
            val context = object : ContextWrapper(base) {
                override fun startForegroundService(service: Intent): ComponentName? {
                    if (refuse) throw ForegroundServiceStartNotAllowedException("not allowed")
                    started.add(service)
                    return service.component
                }
            }
            val runner = AgentTaskRunner(context) { id, reason ->
                runBlocking { coordinator.onTaskFinished(id, "failed", reason) }
            }
            coordinator = TaskCoordinator(db.taskDao(), db.windowDao(), db.allowlistDao(), runner,
                object : ProposalPresenter {
                    override fun showDoThis(taskId: Long, goal: String, sourceLabel: String) {}
                    override fun dismiss(taskId: Long) {}
                }, { 1_000L })
            val first = coordinator.submitUserRequest("first")
            idle()
            val task = db.taskDao().get(first)!!
            assertEquals("failed", task.state)
            assertEquals(AgentTaskRunner.BACKGROUND_START_FAILED, task.result)
            refuse = false
            val second = coordinator.submitUserRequest("second")
            idle()
            assertEquals("running", db.taskDao().get(second)!!.state)
            assertEquals(second, started.single().getLongExtra(AgentTaskRunner.EXTRA_TASK_ID, -1))
        } finally {
            db.close()
        }
    }
}
