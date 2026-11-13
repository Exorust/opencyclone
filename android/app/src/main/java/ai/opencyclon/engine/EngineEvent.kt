package ai.opencyclon.engine

import ai.opencyclon.action.Action
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HoldResult

/** What one engine run did, in order. The engine does not know its task id; the service tags each event. */
sealed class EngineEvent {
    data class Started(val goal: String) : EngineEvent()
    /** One step after the model answered. [caption] is the model's short phrase for the owner, when it gave one. */
    data class Step(val step: Int, val topPackage: String?, val caption: String?) : EngineEvent()
    data class Asking(val question: String) : EngineEvent()
    /** The owner answered [Asking]; null when the wait ended without an answer. */
    data class Answered(val answer: String?) : EngineEvent()
    data class Held(val held: HeldAction) : EngineEvent()
    data class Resolved(val held: HeldAction, val result: HoldResult) : EngineEvent()
    /**
     * An action ran after an approved hold ([held] set), a tool was called, or an app was opened ([held] null when
     * it was not held).
     */
    data class Executed(val held: HeldAction?, val action: Action, val success: Boolean) : EngineEvent()
    data class Finished(val end: TaskEnd) : EngineEvent()
}
