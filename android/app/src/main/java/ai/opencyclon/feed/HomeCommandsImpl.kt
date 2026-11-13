package ai.opencyclon.feed

import ai.opencyclon.gate.ApprovalSession
import android.content.Context

/**
 * The home page's commands. [submit] routes the owner's words in this order: a waiting builder question,
 * a waiting ask-user question, a waiting approval (a plain yes approves, anything else edits it), otherwise
 * a new request. Each dependency is a function so the routing is tested without the runtime.
 */
class HomeCommandsImpl(
    private val builderQuestionWaiting: () -> Boolean,
    private val answerBuilderQuestion: (String) -> Boolean,
    private val engineQuestionWaiting: () -> Boolean,
    private val answerEngine: (String) -> Unit,
    private val session: () -> ApprovalSession,
    /** The spoken-yes path: true when [text] is a plain yes and the waiting approval was approved. */
    private val approveByYes: (String) -> Boolean,
    private val isYes: (String) -> Boolean,
    /**
     * A new request: the pre-start checks, then the request router. The second value is true when the owner's
     * row is already written; otherwise the router writes it.
     */
    private val newRequest: (String, Boolean) -> Unit,
    private val recordOwner: (String) -> Unit,
    private val say: (String) -> Unit,
    private val isUnlocked: () -> Boolean,
    private val cancelTask: (Long) -> Unit,
    private val openLiveView: (Context, Long) -> Unit,
    /** A CONFIRM row's answer or a widget draft-card tap (the widget flow). */
    private val onConfirm: (itemId: Long, yes: Boolean) -> Unit = { _, _ -> },
    /** A sentence of the phone in the thread. */
    private val onNote: (String) -> Unit = say,
    private val onMicrophoneOff: () -> Unit = {},
) : HomeCommands {

    override fun submit(text: String) {
        val words = text.trim()
        if (words.isEmpty()) return
        if (builderQuestionWaiting()) {
            recordOwner(words)
            if (answerBuilderQuestion(words)) return
            // The question went away in between: the words are a new request after all.
            newRequestAfterOwner(words)
            return
        }
        if (engineQuestionWaiting()) {
            recordOwner(words)
            answerEngine(words)
            return
        }
        val pending = session().pending()
        if (pending != null) {
            recordOwner(words)
            if (isYes(words)) {
                if (!approveByYes(words)) say(if (!isUnlocked()) UNLOCK_TO_APPROVE else CANNOT_APPROVE)
                return
            }
            if (session().edit(pending.taskId, words)) return
            newRequestAfterOwner(words)
            return
        }
        newRequest(words, false)
    }

    private fun newRequestAfterOwner(words: String) = newRequest(words, true)

    override suspend fun approve(taskId: Long): Boolean {
        if (!isUnlocked()) return false
        val pending = session().pending() ?: return false
        if (pending.taskId != taskId || pending.held.facts.title == null) return false
        // The engine re-checks the screen after this; a changed screen asks again.
        return session().approve(taskId)
    }

    override fun reject(taskId: Long) {
        session().reject(taskId)
    }

    override fun stop(taskId: Long) = cancelTask(taskId)

    override fun openLive(context: Context, taskId: Long) = openLiveView(context, taskId)

    override fun confirm(itemId: Long, yes: Boolean) = onConfirm(itemId, yes)

    override fun note(text: String) {
        if (text.isNotBlank()) onNote(text.trim())
    }

    override fun microphoneOff() = onMicrophoneOff()

    companion object {
        const val UNLOCK_TO_APPROVE = "Unlock the phone to approve."
        const val CANNOT_APPROVE = "I cannot read who this goes to. Open the live view to check it."
        private val YES = Regex("^\\s*(?:yes|yeah|yep|send it|do it|go ahead)[\\s.!?,]*$", RegexOption.IGNORE_CASE)

        fun isPlainYes(text: String): Boolean = YES.matches(text)
    }
}
