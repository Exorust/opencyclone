package ai.opencyclon.proactive

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ai.opencyclon.tasks.WindowDao
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class Incoming(
    val packageName: String,
    val key: String,
    val conversationKey: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** The thread row shown while this waits for triage; 0 when none was written. */
    val threadId: Long = 0,
    /** The notification's own tap target, kept in memory only for the home page's card (NotificationIntents). */
    val contentIntent: PendingIntent? = null,
)

class NotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var pipeline: Pipeline? = null
        @Volatile var instance: NotificationListener? = null
    }

    override fun onListenerConnected() {
        instance = this
        ai.opencyclon.tasks.ProactiveRuntime.init(applicationContext)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        // Ongoing and system notifications ("Running checks on apps") never get a row and never reach triage.
        if (NotificationGate.isSystemOrOngoing(notification.flags, notification.category,
                hasLauncher = runCatching { packageManager.getLaunchIntentForPackage(sbn.packageName) != null }.getOrDefault(true))) return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        pipeline?.post(Incoming(sbn.packageName, sbn.key,
            sbn.packageName + ":" + (notification.shortcutId ?: title), title, text, sbn.postTime,
            contentIntent = notification.contentIntent))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationIntents.removeKey(sbn.key)
    }
}

/** Which posted notifications are not messages for the owner. */
object NotificationGate {
    private val SYSTEM_CATEGORIES = setOf(Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT)

    /** Ongoing or foreground-service notifications, system categories, and apps without a launcher icon. */
    fun isSystemOrOngoing(flags: Int, category: String?, hasLauncher: Boolean): Boolean =
        flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
            category in SYSTEM_CATEGORIES || !hasLauncher
}

/**
 * The tap target of each notification that has a thread row, in memory only, keyed by the row's id (lane X2). A tap on
 * the card opens the notification in its app, as the owner (no task, no approval; blocked apps too). After a process
 * restart nothing is stored and the app's launcher screen opens instead.
 */
object NotificationIntents {
    const val MAX = 200
    private val byRow = object : LinkedHashMap<Long, Pair<String, PendingIntent>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<String, PendingIntent>>?) = size > MAX
    }

    /** Sends a stored intent (tests replace it). */
    @androidx.annotation.VisibleForTesting
    var send: (Context, PendingIntent, Bundle?) -> Unit = { context, intent, options ->
        intent.send(context, 0, null, null, null, null, options)
    }

    @Synchronized fun put(rowId: Long, key: String, intent: PendingIntent) {
        byRow[rowId] = key to intent
    }

    @Synchronized fun remove(rowId: Long) {
        byRow.remove(rowId)
    }

    @Synchronized fun removeKey(key: String) {
        byRow.entries.removeAll { it.value.first == key }
    }

    @Synchronized fun get(rowId: Long): PendingIntent? = byRow[rowId]?.second

    @Synchronized fun size(): Int = byRow.size

    @Synchronized fun clear() = byRow.clear()

    /** Opens row [rowId]'s notification, else [appPackage]'s launcher screen. Returns false when neither worked. */
    fun open(context: Context, rowId: Long, appPackage: String?): Boolean {
        get(rowId)?.let { intent ->
            val sent = runCatching { send(context, intent, backgroundStartAllowed()) }.isSuccess
            if (sent) return true
            remove(rowId) // cancelled by its app
        }
        val launch = appPackage?.let { runCatching { context.packageManager.getLaunchIntentForPackage(it) }.getOrNull() }
            ?: return false
        return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun backgroundStartAllowed(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else null
}

class Pipeline(
    private val scope: CoroutineScope,
    private val blockedPackages: () -> Set<String>,
    private val rules: Rules,
    private val triage: Triage,
    private val log: NotificationLogDao,
    private val windows: WindowDao,
    private val now: () -> Long,
    /** Writes the open notification row of the thread for a notification that reaches triage; returns its id. */
    private val recordQueued: suspend (Incoming) -> Long = { 0L },
    private val allowed: suspend (Long) -> Boolean = { false },
    private val discardQueued: suspend (Incoming) -> Unit = {}
) {
    private val mutex = Mutex()
    private val queue = mutableListOf<Incoming>()
    private var timer: Job? = null

    fun post(incoming: Incoming): Job = scope.launch { receive(incoming) }

    suspend fun receive(incoming: Incoming) {
        if (PrivacyFilter.shouldDrop(incoming, blockedPackages())) return
        val hidden = rules.hide(incoming)
        if (hidden != null) {
            log.insert(NotificationLogEntity(packageName = incoming.packageName, title = incoming.title,
                result = "hide", ruleText = hidden.text, at = now()))
            windows.count(now(), hidden = 1)
            return
        }
        if (!allowed(now())) return
        val queued = incoming.copy(threadId = recordQueued(incoming))
        if (queued.threadId > 0) queued.contentIntent?.let { NotificationIntents.put(queued.threadId, queued.key, it) }
        mutex.withLock {
            queue.add(queued)
            if (timer == null) timer = scope.launch {
                delay(30_000)
                val batch = mutex.withLock {
                    val items = queue.toList()
                    queue.clear()
                    timer = null
                    items
                }
                // Consent may have been withdrawn, or the window ended, during the batch delay.
                if (allowed(now())) triage.process(batch, rules.textRules())
                else batch.forEach { item ->
                    NotificationIntents.remove(item.threadId)
                    discardQueued(item)
                }
            }
        }
    }
}
