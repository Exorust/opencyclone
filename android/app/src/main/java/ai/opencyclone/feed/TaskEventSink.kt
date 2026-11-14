package ai.opencyclone.feed

import ai.opencyclone.action.Action
import ai.opencyclone.engine.EngineEvent
import ai.opencyclone.gate.HeldAction
import ai.opencyclone.gate.HoldResult
import ai.opencyclone.tasks.TaskCoordinator

/**
 * Turns engine events (tagged with their task id), holds, and task ends into thread rows and live state.
 * Calls must come one at a time, in order (the runtime's record queue does that).
 */
class TaskEventSink(
    private val recorder: ThreadRecorder,
    private val live: LiveStateSource,
    /** The automatic model switch's sentence, read and cleared once (ConfigRepository.takeModelSwitchNotice). */
    private val modelSwitchNotice: () -> String? = { null },
    /** Launcher label of a package, or null when unknown. Last, so callers may pass it as a trailing lambda. */
    private val appLabel: (String?) -> String?,
) {
    private val questionRows = HashMap<Long, Long>()
    private val approvalRows = HashMap<Long, Long>()
    private val sentSomething = HashSet<Long>()
    private val sayWhenStarted = HashMap<Long, String>()
    /** Per task: the approval row of an approved hold, which shows the result when the task ends as done. */
    private val approvedRows = HashMap<Long, Long>()
    /** Tasks that were held at least once: they get no "Opened" receipt. */
    private val heldTasks = HashSet<Long>()
    /** Per task: an app was just opened; the next step's top package is that app. */
    private val openPending = HashSet<Long>()
    /** Per task: the packages the agent opened, and the top package of the last step. */
    private val openedPackages = HashMap<Long, MutableSet<String>>()
    private val lastPackage = HashMap<Long, String>()

    /** Triage's sentence for a proposed task; it is recorded when that task starts. */
    fun sayWhenStarted(taskId: Long, sentence: String) {
        sayWhenStarted[taskId] = sentence
    }

    suspend fun onEvent(taskId: Long, event: EngineEvent) {
        live.onEvent(taskId, event)
        when (event) {
            is EngineEvent.Started -> sayWhenStarted.remove(taskId)?.let { recorder.sentence(it, taskId) }
            is EngineEvent.Asking -> {
                live.setQuestion(QuestionView(taskId, FeedText.asSentence(event.question)))
                questionRows[taskId] = recorder.question(event.question, taskId)
            }
            is EngineEvent.Answered -> {
                live.clearQuestion(taskId)
                questionRows.remove(taskId)?.let {
                    recorder.resolve(it, if (event.answer != null) ThreadState.ANSWERED else ThreadState.FAILED)
                }
            }
            is EngineEvent.Executed -> if (event.success) {
                if (event.action is Action.OpenApp) openPending += taskId
                receipt(taskId, event.held, event.action)
            }
            is EngineEvent.Step -> event.topPackage?.let { pkg ->
                lastPackage[taskId] = pkg
                if (openPending.remove(taskId)) openedPackages.getOrPut(taskId) { HashSet() } += pkg
            }
            is EngineEvent.Held -> heldTasks += taskId
            is EngineEvent.Resolved, is EngineEvent.Finished -> Unit
        }
    }

    private suspend fun receipt(taskId: Long, held: HeldAction?, action: Action) {
        if (action is Action.CallTool) {
            recorder.receipt(taskId, null, "Used ${action.server}", action.tool)
            if (held != null) sentSomething += taskId
            return
        }
        held ?: return
        sentSomething += taskId
        val pkg = held.facts.topPackage
        recorder.receipt(taskId, pkg, FeedText.receiptVerb(held.label ?: FeedText.label(held)),
            FeedText.receiptDetail(held.facts.attachmentCount, held.facts.title, appLabel(pkg),
                held.facts.attachmentNoun, held.facts.composeText != null))
    }

    /**
     * A hold started. [framePath] is the saved picture of the hidden display with the tap marked, or null; the crops
     * of the attached items lie beside it (`EvidenceStore.saveHold`).
     */
    suspend fun onHold(taskId: Long, held: HeldAction, framePath: String?) {
        // A hold that follows an approved one whose re-check failed replaces it.
        approvalRows.remove(taskId)?.let { recorder.resolve(it, ThreadState.EXPIRED) }
        val pkg = held.facts.topPackage
        val heading = FeedText.heading(held, appLabel(pkg))
        val word = FeedText.actionWord(held)
        val canApprove = FeedText.canApprove(held)
        val evidence = Evidence(count = held.facts.attachmentCount, composeText = held.facts.composeText,
            thumbs = EvidenceStore.thumbsBeside(framePath), framePath = framePath)
        approvalRows[taskId] = recorder.approval(taskId, heading, word, pkg, evidence, canApprove)
        // F5.14: one sentence under an approval whose target cannot be read.
        if (!canApprove) recorder.sentence(FeedText.UNREADABLE_TARGET, taskId)
        live.setApproval(ApprovalView(taskId, heading, word, pkg, evidence, canApprove),
            StatusSentence.Approval(held.facts.attachmentCount, held.facts.title, FeedText.label(held),
                held.facts.attachmentNoun, held.facts.composeText != null))
    }

    suspend fun onHoldResolved(taskId: Long, result: HoldResult, expired: Boolean) {
        live.clearApproval(taskId)
        val row = approvalRows.remove(taskId) ?: return
        if (result == HoldResult.Approve) approvedRows[taskId] = row
        recorder.resolve(row, when {
            result == HoldResult.Approve -> ThreadState.APPROVED
            expired -> ThreadState.EXPIRED
            else -> ThreadState.REJECTED
        })
    }

    /**
     * The coordinator closed a task. [previousState] is its state before; [expired] is true when an approval
     * waited too long. Only a task that held the slot gets a sentence; a dropped proposal is silent.
     */
    suspend fun onTaskEnded(taskId: Long, previousState: String, state: String, result: String?, expired: Boolean) {
        live.taskEnded(taskId)
        approvalRows.remove(taskId)
        questionRows.remove(taskId)
        sayWhenStarted.remove(taskId)
        recorder.closeTask(taskId)
        val sent = sentSomething.remove(taskId)
        val approvedRow = approvedRows.remove(taskId)
        val held = heldTasks.remove(taskId)
        openPending.remove(taskId)
        val opened = openedPackages.remove(taskId).orEmpty()
        val last = lastPackage.remove(taskId)
        if (previousState !in SLOT_STATES) return
        when (state) {
            "done" -> {
                val sentence = FeedText.firstSentence(result)
                // An action that needed no approval: one receipt when the task only ended in an app it opened.
                if (!held && last != null && last in opened) {
                    appLabel(last)?.let { recorder.receipt(taskId, last, FeedText.OPENED, it) }
                }
                // The approved action ran: its approval row carries the result for the home page.
                approvedRow?.let { row -> recorder.update(row) { it.copy(detail = sentence) } }
                recorder.result(taskId, sentence)
            }
            "failed" -> recorder.sentence(FeedText.failure(result), taskId)
            "cancelled" -> recorder.sentence(when {
                expired -> FeedText.EXPIRED
                sent -> FeedText.STOPPED_AFTER_SENDING
                else -> FeedText.STOPPED
            }, taskId)
        }
        // The engine switched to another model during the task: said once, after the task's own sentence.
        modelSwitchNotice()?.takeIf { it.isNotBlank() }?.let { recorder.sentence(it, taskId) }
    }

    companion object {
        val SLOT_STATES = setOf("running", "paused", "awaiting_approval")
        /** Lane N1's sentence for a task that a stopped process left open. */
        const val RESTART = TaskCoordinator.STOPPED_RESULT
    }
}
