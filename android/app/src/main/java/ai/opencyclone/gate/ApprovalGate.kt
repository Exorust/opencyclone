package ai.opencyclone.gate

import ai.opencyclone.action.Action

data class HeldAction(
    val action: Action,
    val reason: HoldReason,
    val label: String?,
    val bounds: IntArray?,
    val tapX: Int?, val tapY: Int?,
    val facts: ScreenFacts
) {
    /** The label as shown to the owner; `label` itself is what the re-check on Approve compares. */
    val displayLabel: String? get() = label?.let(::sentenceCaseIfShouting)

    companion object {
        /** "SEND" -> "Send", "PLACE ORDER" -> "Place order"; mixed case and single letters stay as they are. */
        fun sentenceCaseIfShouting(label: String): String {
            val letters = label.filter { it.isLetter() }
            if (letters.length < 2 || letters.any { !it.isUpperCase() }) return label
            val lower = label.lowercase(java.util.Locale.ROOT)
            val first = lower.indexOfFirst { it.isLetter() }
            return lower.substring(0, first) + lower[first].titlecaseChar() + lower.substring(first + 1)
        }
    }
}

/** [OPEN_LINK]: a link with a scheme that can act without a confirmation screen (F18.4). */
enum class HoldReason { COMMIT_WORD, UNREADABLE_TARGET, ENTER_IN_TEXT_FIELD, FINAL_ACTION, TOOL_CALL, OPEN_LINK }

sealed class GateDecision {
    object Allow : GateDecision()
    data class Hold(val held: HeldAction) : GateDecision()
    data class Fail(val message: String) : GateDecision()
}

class ApprovalGate(
    private val reader: ScreenReader,
    private val blockedPackages: () -> Set<String>,
    private val displayId: () -> Int,
    private val isReadOnlyTool: (server: String, tool: String) -> Boolean = { _, _ -> false },
    private val toPixels: (x: Int, y: Int) -> Pair<Int, Int>
) {
    companion object {
        const val UNREADABLE_MESSAGE = "Cannot read the screen"
        const val PROTECTED_MESSAGE = "Cannot open protected app"
        const val CALL_LABEL = "Call"
        const val OPEN_LINK_LABEL = "Open link"
        /** ACTION_VIEW of these schemes opens a page, a map, a dialler or a filled-in composer; nothing completes. */
        val VIEW_SCHEMES = setOf("https", "http", "geo", "sms", "smsto", "mailto", "tel")
    }

    fun evaluate(action: Action, alreadyApproved: Boolean): GateDecision =
        if (alreadyApproved) GateDecision.Allow else inspect(action)

    // Once held, a changed target needs a new approval even if its new label is harmless.
    internal fun refresh(held: HeldAction): GateDecision = inspect(held.action, held.reason)

    private fun inspect(action: Action, forceHold: HoldReason? = null): GateDecision {
        // Tools do not operate on a display, including during approval rechecks.
        if (action is Action.CallTool) {
            if (forceHold == null && isReadOnlyTool(action.server, action.tool)) return GateDecision.Allow
            return GateDecision.Hold(HeldAction(action, HoldReason.TOOL_CALL,
                "${action.server}.${action.tool}", null, null, null, ScreenFacts(null, null, null)))
        }
        val display = displayId()
        if (action is Action.OpenLink) return inspectLink(action, display, forceHold)
        val screen = when (val result = reader.screenFacts(display)) {
            ScreenReader.Result.Unreadable -> return if (forceHold == null && canRunWithoutScreen(action)) {
                GateDecision.Allow
            } else GateDecision.Fail(UNREADABLE_MESSAGE)
            is ScreenReader.Result.Ok -> result.value
        }
        if (screen.topPackage in blockedPackages()) return GateDecision.Fail(PROTECTED_MESSAGE)
        val point = when (action) {
            is Action.Tap -> toPixels(action.x, action.y)
            is Action.LongPress -> toPixels(action.x, action.y)
            else -> null
        }
        // A tap is attributed to the window it lands in, which can differ from the display's top app window.
        val facts = if (point == null) screen else when (val at = reader.screenFactsAt(display, point.first, point.second)) {
            ScreenReader.Result.Unreadable -> return GateDecision.Fail(UNREADABLE_MESSAGE)
            is ScreenReader.Result.Ok -> at.value
        }
        if (facts.topPackage in blockedPackages()) return GateDecision.Fail(PROTECTED_MESSAGE)
        // A typed line break is sent as the Enter key (InputInjector.typeViaKeyEvents), so it submits like Enter.
        val typesLineBreak = action is Action.TypeText && action.text.any { it == '\n' || it == '\r' }
        val key = (action as? Action.KeyEvent)?.key?.lowercase(java.util.Locale.ROOT)
        val enters = key == "enter" || key == "numpad_enter"
        val activates = enters || key == "space" || key == "dpad_center"
        val target = when {
            point != null -> reader.elementAt(display, point.first, point.second)
            activates -> reader.focusedElement(display)
            typesLineBreak -> reader.focusedElement(display)
            else -> return GateDecision.Allow
        }
        val element = when (target) {
            ScreenReader.Result.Unreadable -> return GateDecision.Fail(UNREADABLE_MESSAGE)
            is ScreenReader.Result.Ok -> target.value
        }
        // A tap by number (F18.1) also carries the element's own label from the list and its window's app.
        val ownLabel = (action as? Action.Tap)?.takeIf { it.index != null }?.label
            ?: (action as? Action.LongPress)?.takeIf { it.index != null }?.label
        val expectedPackage = (action as? Action.Tap)?.takeIf { it.index != null }?.targetPackage
            ?: (action as? Action.LongPress)?.takeIf { it.index != null }?.targetPackage
        val matchingLabel = element?.let { listOf(it.text, it.description, it.idName).firstOrNull(CommitWords::matches) }
            ?: ownLabel?.takeIf { element != null && CommitWords.matches(it) }
        // Activation keys click the input-focused control just as a tap would.
        val clicksControl = point != null || (activates && element?.editable != true)
        val reason = when {
            clicksControl && element == null -> HoldReason.UNREADABLE_TARGET
            clicksControl && matchingLabel != null -> HoldReason.COMMIT_WORD
            // Hit-testing stays the check that the tap lands in the window the element was listed in.
            point != null && expectedPackage != null && facts.topPackage != expectedPackage -> HoldReason.UNREADABLE_TARGET
            clicksControl && element != null && !element.editable &&
                element.text.isNullOrBlank() && element.description.isNullOrBlank() -> HoldReason.UNREADABLE_TARGET
            (enters || typesLineBreak) && element?.editable == true -> HoldReason.ENTER_IN_TEXT_FIELD
            // Preserve the line-break hold; the executor also requires editable focus before injecting text.
            typesLineBreak -> HoldReason.ENTER_IN_TEXT_FIELD
            else -> forceHold ?: return GateDecision.Allow
        }
        val label = matchingLabel ?: element?.let { it.text ?: it.description ?: it.idName } ?: ownLabel?.takeIf { it.isNotBlank() }
        // ponytail: assumes the typed text lands at the end of the field's present text (the cursor is not read).
        val heldFacts = if (typesLineBreak && action is Action.TypeText) {
            val typed = action.text.substringBefore('\n').substringBefore('\r')
            val whole = (facts.composeText.orEmpty() + typed).takeIf { it.isNotBlank() }
            facts.copy(composeText = whole?.let(ScreenFacts::capCompose))
        } else facts
        return GateDecision.Hold(HeldAction(action, reason, label,
            element?.let { intArrayOf(it.left, it.top, it.right, it.bottom) },
            point?.first, point?.second, heldFacts))
    }

    /**
     * F18.4: a call is always held ("Call", title the number); a link of a scheme outside [VIEW_SCHEMES] is held
     * ("Open link", title the link); a view link of those schemes is allowed. The protected list is checked first.
     */
    private fun inspectLink(action: Action.OpenLink, display: Int, forceHold: HoldReason?): GateDecision {
        val screen = reader.screenFacts(display)
        if (screen is ScreenReader.Result.Ok && screen.value.topPackage in blockedPackages()) {
            return GateDecision.Fail(PROTECTED_MESSAGE)
        }
        if (action.targetPackage != null && action.targetPackage in blockedPackages()) return GateDecision.Fail(PROTECTED_MESSAGE)
        val (reason, label, title) = when {
            action.call -> Triple(HoldReason.COMMIT_WORD, CALL_LABEL, action.number)
            action.scheme !in VIEW_SCHEMES -> Triple(HoldReason.OPEN_LINK, OPEN_LINK_LABEL, ScreenFacts.capCompose(action.uri))
            forceHold != null -> Triple(forceHold, OPEN_LINK_LABEL, ScreenFacts.capCompose(action.uri))
            else -> return GateDecision.Allow
        }
        return GateDecision.Hold(HeldAction(action, reason, label, null, null, null,
            ScreenFacts(action.targetPackage, title, null)))
    }

    fun stillMatches(held: HeldAction): Boolean {
        val current = (refresh(held) as? GateDecision.Hold)?.held ?: return false
        return current.label == held.label && current.bounds.contentEquals(held.bounds) &&
            current.tapX == held.tapX && current.tapY == held.tapY &&
            // Title, package, item count and noun, and the text to be sent must all be unchanged.
            current.facts.forRecheck() == held.facts.forRecheck() &&
            current.reason == held.reason
    }

    private fun canRunWithoutScreen(action: Action): Boolean = when (action) {
        is Action.OpenApp, is Action.OpenLink, is Action.Wait, is Action.Note, is Action.Remember,
        is Action.ListApps, is Action.AskUser, is Action.HandoffUser, is Action.Fail -> true
        else -> false
    }
}
