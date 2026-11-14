package ai.opencyclone.tasks

import ai.opencyclone.HomeForeground
import ai.opencyclone.R
import ai.opencyclone.ui.ControlPanelActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * A question of the app builder or the widget builder while the owner is in another app: the same kind of
 * notification the engine's questions get ("<app name> has a question", the question, a tap opens the home page).
 * Removed when the question is answered or expires.
 */
object BuilderQuestionCard {
    /** The engine's question channel (AgentService.CHANNEL_ASK). */
    const val CHANNEL_ID = "opencyclone_ask_user"
    private const val CHANNEL_NAME = "Questions"
    const val NOTIFICATION_ID = 1_005

    /** Posts the card unless the home page is in front (it shows the question there). Returns true when posted. */
    fun post(context: Context, question: String, homeInFront: Boolean = HomeForeground.resumed): Boolean {
        if (homeInFront) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            ControlPanelActivity.homeIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.x2_has_question, context.getString(R.string.app_name)))
            .setContentText(question)
            .setStyle(Notification.BigTextStyle().bigText(question))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build())
        return true
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
