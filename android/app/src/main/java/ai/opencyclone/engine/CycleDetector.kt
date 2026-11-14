package ai.opencyclone.engine

import ai.opencyclone.action.Action

/**
 * Detects a task going in circles (F18.3): a cycle of 1 to 4 actions that occurred 3 times in a row with the same
 * outcome (same action type and target, same screen after each). Waits and observations are not members.
 */
class CycleDetector {
    data class Entry(val type: String, val target: String, val screenAfter: Int?)

    private val entries = ArrayDeque<Entry>()

    fun add(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > MAX_LENGTH * REPEATS) entries.removeFirst()
    }

    /** True when the last entries are one cycle repeated [REPEATS] times. An unknown screen never matches. */
    fun looping(): Boolean = (1..MAX_LENGTH).any { length ->
        val n = entries.size
        if (n < length * REPEATS) return@any false
        val tail = entries.toList().subList(n - length * REPEATS, n)
        tail.all { it.screenAfter != null } &&
            (0 until length * (REPEATS - 1)).all { tail[it] == tail[it + length] }
    }

    companion object {
        const val MAX_LENGTH = 4
        const val REPEATS = 3
        const val MESSAGE = "I was going in circles, so I stopped. Nothing more was sent."

        /** The target of an action for cycle comparison; null for waits and observations, which are not members. */
        fun targetOf(action: Action): String? = when (action) {
            is Action.Tap -> action.targetKey ?: "${action.x / 20},${action.y / 20}"
            is Action.LongPress -> action.targetKey ?: "${action.x / 20},${action.y / 20}"
            is Action.Swipe -> action.direction
            is Action.KeyEvent -> action.key
            is Action.TypeText -> action.text
            is Action.OpenApp -> action.appName
            is Action.OpenLink -> action.uri + if (action.call) " call" else ""
            is Action.CallTool -> "${action.server}.${action.tool} ${action.argumentsJson}"
            is Action.Wait, is Action.Finish, is Action.Fail, is Action.AskUser, is Action.HandoffUser,
            is Action.Note, is Action.Remember, is Action.ListApps -> null
        }
    }
}
