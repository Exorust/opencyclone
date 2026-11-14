package ai.opencyclone.tasks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.opencyclone.R
import ai.opencyclone.gate.DeviceLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The "Do this?" card for a proposal that may not start by itself. */
class ProposalCard(private val context: Context, private val onShown: () -> Unit = {}) : ProposalPresenter {
    companion object {
        const val CHANNEL_ID = "proposals"
        private const val CHANNEL_NAME = "Suggestions"
        private const val ID_BASE = 40_000
        const val TITLE = "Do this?"
        const val ACTION_DO_THIS = "Do this"
        const val ACTION_DISMISS = "Dismiss"

        fun notificationId(taskId: Long): Int = ID_BASE + (taskId % 10_000).toInt()
    }

    override fun showDoThis(taskId: Long, goal: String, sourceLabel: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val accept = ProposalReceiver.pendingIntent(context, ProposalReceiver.ACTION_ACCEPT, taskId)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(TITLE)
            .setContentText(goal)
            .setSubText(sourceLabel)
            .setStyle(Notification.BigTextStyle().bigText(goal))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(accept)
            // Starting the task needs an unlocked phone, the same as Approve on an approval card.
            .addAction(Notification.Action.Builder(null, ACTION_DO_THIS, accept).setAuthenticationRequired(true).build())
            .addAction(Notification.Action.Builder(null, ACTION_DISMISS,
                ProposalReceiver.pendingIntent(context, ProposalReceiver.ACTION_DISMISS, taskId)).build())
            .setTimeoutAfter(TaskCoordinator.EXPIRY_MS)
            .build()
        manager.notify(notificationId(taskId), notification)
        onShown()
    }

    override fun dismiss(taskId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(taskId))
    }
}

class ProposalReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "ai.opencyclone.tasks.ACCEPT_PROPOSAL"
        const val ACTION_DISMISS = "ai.opencyclone.tasks.DISMISS_PROPOSAL"
        const val EXTRA_TASK_ID = "task_id"

        fun pendingIntent(context: Context, action: String, taskId: Long): PendingIntent {
            val intent = Intent(context, ProposalReceiver::class.java).setAction(action).putExtra(EXTRA_TASK_ID, taskId)
            val code = ProposalCard.notificationId(taskId) * 2 + if (action == ACTION_ACCEPT) 0 else 1
            return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId < 0) return
        when (intent.action) {
            ACTION_ACCEPT -> {
                // The card's body tap sends the same broadcast; on a locked phone it must not start the task.
                if (!DeviceLock.isUnlocked(context)) return
                val runtime = ProactiveRuntime.init(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // The same call as the home page's "Do it": the task starts and the home page's row is answered.
                        runtime.acceptProposal(taskId)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_DISMISS -> {
                // "Dismiss" ends the proposal for good, the same as the home page's "Not now".
                context.getSystemService(NotificationManager::class.java).cancel(ProposalCard.notificationId(taskId))
                val runtime = ProactiveRuntime.init(context)
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        runtime.declineProposal(taskId)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
