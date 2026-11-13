package ai.opencyclon.gate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import ai.opencyclon.HomeForeground
import ai.opencyclon.R
import ai.opencyclon.action.Action
import ai.opencyclon.feed.FeedText
import ai.opencyclon.ui.ControlPanelActivity
import ai.opencyclon.ui.ViewActivity

/**
 * The notification for a held action: the action word with its target ("Send to Alex"), "Change", and "Not now"
 * (Review in place of the action word when the title is unreadable). A tap on the body opens the home page.
 */
object ApprovalCard {
    const val CHANNEL_ID = "approvals"
    private const val CHANNEL_NAME = "Approvals"
    /** The same card while the home page is resumed: still on the lock screen, but no sound and no heads-up. */
    const val QUIET_CHANNEL_ID = "approvals_quiet"
    private const val QUIET_CHANNEL_NAME = "Approvals while the app is open"
    private const val ID_BASE = 20_000
    private const val EXPIRED_ID_BASE = 30_000
    const val PUBLIC_TEXT = "Approval needed"
    /** The approve button's word when the screen gave no readable label. */
    const val ACTION_APPROVE = "Approve"
    const val ACTION_EDIT = "Change"
    const val ACTION_REJECT = "Not now"
    const val ACTION_REVIEW = "Review"
    const val EXPIRED_PREFIX = "Expired: "

    fun title(held: HeldAction, appLabel: String): String = (held.action as? Action.OpenLink)?.let { link ->
        // F18.4: "Call +14155550100"; "whatsapp link in WhatsApp".
        if (link.call) "${ApprovalGate.CALL_LABEL} ${link.number}" else "${link.scheme} link in $appLabel"
    } ?: when (held.reason) {
        HoldReason.COMMIT_WORD -> labelled(held, held.displayLabel ?: "Tap", appLabel)
        HoldReason.UNREADABLE_TARGET -> "Tap on an unreadable spot in $appLabel"
        HoldReason.ENTER_IN_TEXT_FIELD -> "Submit the typed text in $appLabel"
        HoldReason.FINAL_ACTION, HoldReason.TOOL_CALL, HoldReason.OPEN_LINK -> labelled(held, held.displayLabel ?: held.action.typeName, appLabel)
    }

    /**
     * With a title and a count or the text to be sent: the home page's question ("3 photos to Alex. Send them?",
     * "Send this to Alex?"). Otherwise "<label> in <app> to <title>" or "<label> in <app>" (the walk scenarios read it).
     */
    private fun labelled(held: HeldAction, label: String, appLabel: String): String {
        val facts = held.facts
        if (held.action !is Action.CallTool && facts.title != null && (facts.attachmentCount != null || facts.composeText != null)) {
            return FeedText.heading(held, appLabel)
        }
        return "$label in $appLabel" + (facts.title?.let { " to $it" } ?: "")
    }

    /** "To: Alex  3 photos", then the text that will be sent on its own line. */
    fun text(held: HeldAction): String {
        val facts = held.facts
        val first = buildList {
            facts.title?.let { add("To: $it") }
            facts.attachmentCount?.let { add(FeedText.items(it, facts.attachmentNoun)) }
        }.joinToString("  ")
        val compose = facts.composeText?.let { "\u201C$it\u201D" }
        return listOf(first, compose.orEmpty()).filter { it.isNotEmpty() }.joinToString("\n")
            .ifEmpty { "Check the screen before approving." }
    }

    fun appLabel(context: Context, packageName: String?): String {
        if (packageName == null) return "the app"
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    fun notificationId(taskId: Long): Int = ID_BASE + (taskId % 10_000).toInt()

    /** The approve button: the home page's action word ("Send to Alex"), or [ACTION_APPROVE] without a readable label. */
    fun approveLabel(held: HeldAction): String =
        if (held.action !is Action.CallTool && held.displayLabel.isNullOrBlank()) ACTION_APPROVE else FeedText.actionWord(held)

    /** The channel for a new card: quiet while the home page, which shows the approval, is resumed. */
    fun channelFor(homeResumed: Boolean): String = if (homeResumed) QUIET_CHANNEL_ID else CHANNEL_ID

    fun post(context: Context, taskId: Long, held: HeldAction, frame: Bitmap?, displaySize: Point?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        val title = title(held, appLabel(context, held.facts.topPackage))
        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PUBLIC_TEXT)
            .build()
        val channel = channelFor(HomeForeground.resumed)
        val home = ControlPanelActivity.homeIntent(context)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text(held))
            .setContentIntent(PendingIntent.getActivity(context, notificationId(taskId) + 2, home,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(true)
        frame?.let { builder.setStyle(Notification.BigPictureStyle().bigPicture(marked(it, held, displaySize)).setSummaryText(text(held))) }

        if (held.facts.title != null) {
            builder.addAction(Notification.Action.Builder(null, approveLabel(held),
                ApprovalReceiver.pendingIntent(context, ApprovalReceiver.ACTION_APPROVE, taskId))
                .setAuthenticationRequired(true).build())
        } else {
            val review = Intent(context, ViewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.addAction(Notification.Action.Builder(null, ACTION_REVIEW,
                PendingIntent.getActivity(context, notificationId(taskId), review,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        val edit = Intent(context, EditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EditActivity.EXTRA_TASK_ID, taskId)
            .putExtra(EditActivity.EXTRA_SUMMARY, "$title\n${text(held)}")
        builder.addAction(Notification.Action.Builder(null, ACTION_EDIT,
            PendingIntent.getActivity(context, notificationId(taskId) + 1, edit,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAuthenticationRequired(true).build())
        builder.addAction(Notification.Action.Builder(null, ACTION_REJECT,
            ApprovalReceiver.pendingIntent(context, ApprovalReceiver.ACTION_REJECT, taskId)).build())

        manager.notify(notificationId(taskId), builder.build())
    }

    fun cancel(context: Context, taskId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(taskId))
    }

    fun postExpired(context: Context, taskId: Long, cardTitle: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(manager)
        manager.notify(EXPIRED_ID_BASE + (taskId % 10_000).toInt(), Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(EXPIRED_PREFIX + cardTitle)
            .setAutoCancel(true)
            .build())
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
        }
        if (manager.getNotificationChannel(QUIET_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(QUIET_CHANNEL_ID, QUIET_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                })
        }
    }

    /** A copy of the frame with a red circle at the tap point, scaled from display pixels to frame pixels. */
    private fun marked(frame: Bitmap, held: HeldAction, displaySize: Point?): Bitmap {
        val x = held.tapX ?: return frame
        val y = held.tapY ?: return frame
        val copy = frame.copy(Bitmap.Config.ARGB_8888, true) ?: return frame
        val sx = displaySize?.takeIf { it.x > 0 }?.let { copy.width.toFloat() / it.x } ?: 1f
        val sy = displaySize?.takeIf { it.y > 0 }?.let { copy.height.toFloat() / it.y } ?: 1f
        val radius = copy.width / 18f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFE53935.toInt()
            strokeWidth = radius / 4f
        }
        Canvas(copy).drawCircle(x * sx, y * sy, radius, paint)
        return copy
    }
}
