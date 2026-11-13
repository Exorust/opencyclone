package ai.opencyclon.tasks

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.opencyclon.AgentService
import ai.opencyclon.App

/**
 * Runs coordinator tasks on the existing AgentService. Calls are posted so they never run inside the coordinator lock.
 * [onStartFailed] reports a task that could not start, with a reason for the owner; it must end the task.
 */
class AgentTaskRunner(
    private val context: Context,
    private val onStartFailed: (taskId: Long, reason: String) -> Unit
) : TaskRunner {
    companion object {
        private const val TAG = "AgentTaskRunner"
        const val ACTION_RUN_TASK = "ai.opencyclon.RUN_TASK"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_GOAL = "goal"
        const val BACKGROUND_START_FAILED = "I could not start in the background. Open the app once."
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var current: Long? = null

    override fun start(taskId: Long, goal: String) {
        current = taskId
        main.post {
            val service = App.agentService
            if (service != null) {
                service.runAgentNow(taskId, goal)
            } else {
                Log.i(TAG, "AgentService not running, starting it for task $taskId")
                // Android 12+ throws ForegroundServiceStartNotAllowedException (an IllegalStateException) for a
                // background start without an exemption. It is thrown here on the main thread, where the
                // coordinator's try/catch cannot see it, so it must be caught here or the process dies.
                try {
                    context.startForegroundService(Intent(context, AgentService::class.java)
                        .setAction(ACTION_RUN_TASK)
                        .putExtra(EXTRA_TASK_ID, taskId)
                        .putExtra(EXTRA_GOAL, goal))
                } catch (error: IllegalStateException) {
                    failStart(taskId, error)
                } catch (error: SecurityException) {
                    failStart(taskId, error)
                }
            }
        }
    }

    private fun failStart(taskId: Long, error: Exception) {
        Log.w(TAG, "Cannot start AgentService for task $taskId: ${error.message}")
        if (current == taskId) current = null
        onStartFailed(taskId, BACKGROUND_START_FAILED)
    }

    override fun stop(taskId: Long) {
        if (current != taskId) return
        current = null
        main.post { App.agentService?.stopAgent() }
    }
}
