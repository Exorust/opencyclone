package ai.opencyclon.gate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.opencyclon.tasks.ProactiveRuntime

/** Approve and Reject buttons of the approval card. */
class ApprovalReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ApprovalReceiver"
        const val ACTION_APPROVE = "ai.opencyclon.gate.APPROVE"
        const val ACTION_REJECT = "ai.opencyclon.gate.REJECT"
        const val EXTRA_TASK_ID = "task_id"

        fun pendingIntent(context: Context, action: String, taskId: Long): PendingIntent {
            val intent = Intent(context, ApprovalReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId)
            val code = ApprovalCard.notificationId(taskId) * 4 + if (action == ACTION_APPROVE) 2 else 3
            return PendingIntent.getBroadcast(context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId < 0) return
        val session = ProactiveRuntime.init(context).session
        val handled = when (intent.action) {
            // The button already asks for unlock; this also refuses a broadcast that arrives while locked.
            ACTION_APPROVE -> DeviceLock.isUnlocked(context) && session.approve(taskId)
            ACTION_REJECT -> session.reject(taskId)
            else -> return
        }
        Log.i(TAG, "${intent.action} for task $taskId handled=$handled")
        if (!handled) ApprovalCard.cancel(context, taskId)
    }
}
