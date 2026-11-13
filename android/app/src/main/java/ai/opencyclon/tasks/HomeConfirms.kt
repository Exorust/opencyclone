package ai.opencyclon.tasks

import ai.opencyclon.feed.ConfirmRef
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.feed.ThreadRecorder
import ai.opencyclon.feed.ThreadState

/**
 * The CONFIRM rows that are not a widget's sources question: the "Do this?" of a proposal, "Set up" when no model
 * is set, and "Allow" when the microphone is off. Answers come from the home page ([confirm]) and, for a proposal,
 * from its notification ([accept], [decline]); either place updates the other.
 */
class HomeConfirms(
    private val recorder: ThreadRecorder,
    private val coordinator: TaskCoordinator,
    private val isUnlocked: () -> Boolean,
    private val say: (String) -> Unit,
    private val openSetUp: () -> Unit,
    private val openMicrophoneSettings: () -> Unit,
    /** Any other CONFIRM row or a widget draft card: the widget flow decides. */
    private val widgets: (itemId: Long, yes: Boolean) -> Unit,
) {
    /** A proposal was shown (new, or the same sender again): its row under the notification card, or its new text. */
    suspend fun proposalShown(taskId: Long, goal: String) {
        val text = question(goal)
        val open = recorder.openConfirmOfTask(taskId)
        if (open != null) {
            recorder.update(open.id) { it.copy(text = text) }
            return
        }
        recorder.confirm(text, DO_IT, ConfirmRef.PROPOSAL, taskId)
    }

    /** "Do it" (home page) or "Do this" (notification): the same coordinator call and the same unlock rule. */
    suspend fun accept(taskId: Long): Boolean {
        if (!isUnlocked()) {
            say(UNLOCK_TO_START)
            return false
        }
        coordinator.acceptProposal(taskId)
        val row = recorder.openConfirmOfTask(taskId) ?: return true
        // The proposal expired or was declined in between: its row closes through the task's end instead.
        recorder.resolve(row.id, ThreadState.ANSWERED)
        return true
    }

    /** "Not now" (home page) or "Dismiss" (notification): the proposal ends for good. */
    suspend fun decline(taskId: Long) {
        // First the row, so the task's end (closeTask) finds it answered and leaves it REJECTED.
        recorder.openConfirmOfTask(taskId)?.let { recorder.resolve(it.id, ThreadState.REJECTED) }
        coordinator.declineProposal(taskId)
    }

    /** HomeCommands.confirm: the row's stored reference says what the answer does, never its text. */
    suspend fun confirm(itemId: Long, yes: Boolean) {
        val item = recorder.get(itemId)
        if (item == null || item.kind != ThreadKind.CONFIRM) {
            widgets(itemId, yes)
            return
        }
        when (item.title) {
            ConfirmRef.PROPOSAL -> {
                val taskId = item.taskId ?: return
                if (item.state != ThreadState.OPEN) return
                if (yes) accept(taskId) else decline(taskId)
            }
            ConfirmRef.SET_UP, ConfirmRef.MICROPHONE -> {
                if (yes) {
                    if (item.title == ConfirmRef.SET_UP) openSetUp() else openMicrophoneSettings()
                    recorder.resolve(itemId, ThreadState.ANSWERED)
                } else {
                    recorder.resolve(itemId, ThreadState.REJECTED)
                }
            }
            else -> widgets(itemId, yes)
        }
    }

    /** No model: one sentence and a "Set up" row. */
    suspend fun noModel() {
        recorder.sentence(NO_MODEL)
        recorder.confirm("", SET_UP, ConfirmRef.SET_UP)
    }

    /** The microphone is off: one sentence and an "Allow" row. */
    suspend fun microphoneOff() {
        recorder.sentence(MIC_OFF)
        recorder.confirm("", ALLOW, ConfirmRef.MICROPHONE)
    }

    companion object {
        const val DO_IT = "Do it"
        const val SET_UP = "Set up"
        const val ALLOW = "Allow"
        const val NO_MODEL = "No model is set, so I did nothing."
        const val MIC_OFF = "The microphone is off, so I can't listen."
        const val UNLOCK_TO_START = "Unlock the phone to start it."

        /** "Reply to Alex that dinner at 7 works" gives "Reply to Alex that dinner at 7 works?". */
        fun question(goal: String): String {
            val t = goal.trim().trimEnd('.', '!', ' ')
            if (t.isEmpty()) return "Do this?"
            return if (t.endsWith("?")) t else "$t?"
        }
    }
}
