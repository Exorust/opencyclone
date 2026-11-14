package ai.opencyclone.engine

/**
 * The steps of the last task, for the walk harness's `state` output (F18): the action, whether the target was
 * addressed by number, the element's label, the quiet-screen wait, and the feedback given to the model.
 * ponytail: one process-wide log of the last task only; a second engine running at the same time would mix in.
 */
object StepLog {
    data class Fact(
        val step: Int,
        val action: String,
        val byNumber: Boolean = false,
        val number: Int? = null,
        val label: String? = null,
        val waitMs: Long? = null,
        val feedback: String? = null,
        val success: Boolean = true,
        /**
         * What the cycle check compared for this action (F18.3): "type target @screen", the screen being the identity
         * of the screen after the action (ElementList.identity in hex). Null for actions that are not cycle members.
         */
        val cycle: String? = null,
    )

    private val facts = mutableListOf<Fact>()

    @Synchronized fun reset() = facts.clear()

    @Synchronized fun add(fact: Fact) {
        facts.add(fact)
        if (facts.size > MAX) facts.removeAt(0)
    }

    /** Changes the newest fact of [step], if any (the screen after an action is known one step later). */
    @Synchronized fun updateStep(step: Int, change: (Fact) -> Fact) {
        val i = facts.indexOfLast { it.step == step }
        if (i >= 0) facts[i] = change(facts[i])
    }

    /** Changes the newest fact (the wait is known only after the action ran). */
    @Synchronized fun updateLast(change: (Fact) -> Fact) {
        if (facts.isNotEmpty()) facts[facts.lastIndex] = change(facts.last())
    }

    @Synchronized fun lastTask(): List<Fact> = facts.toList()

    private const val MAX = 100
}
