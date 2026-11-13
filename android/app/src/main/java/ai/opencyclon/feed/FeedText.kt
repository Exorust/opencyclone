package ai.opencyclon.feed

import ai.opencyclon.action.Action
import ai.opencyclon.gate.HeldAction
import ai.opencyclon.gate.HoldReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The words the thread and the live state show. Pure functions so every form is unit-tested. */
object FeedText {
    const val MAX_RESULT = 120
    /** The noun of attached items when the screen did not name it. */
    const val ITEMS = "items"
    const val STOPPED = "I stopped. Nothing was sent."
    const val STOPPED_AFTER_SENDING = "I stopped."
    const val EXPIRED = "The approval expired. Nothing was sent."
    const val DONE = "Done."
    const val WORKING = "Working on it"
    const val FALLBACK_RECEIPT = "Done"
    const val BUILD_ETA = "~1 min"
    const val OPEN_APP = "tap to open"

    /** The owner-facing verb of a held action: the gate's label in sentence case, or a word for its reason. */
    fun label(held: HeldAction): String = when {
        held.action is Action.CallTool -> "Use"
        held.reason == HoldReason.UNREADABLE_TARGET -> "Tap"
        held.reason == HoldReason.ENTER_IN_TEXT_FIELD && held.displayLabel == null -> "Submit"
        else -> held.displayLabel?.takeIf { it.isNotBlank() } ?: "Tap"
    }

    /** False when the recipient or target title is unreadable; an unreadable count does not hide the button. */
    fun canApprove(held: HeldAction): Boolean = held.facts.title != null

    /**
     * The approval question. It names the recipient: "9 photos to Alex. Send them?" with a count,
     * "Send this to Alex?" with the text to be sent, "Send in WhatsApp to Alex?" with the title only,
     * "Send to Alex?" when the app is unknown, "Send in WhatsApp?" when the title is unreadable.
     */
    fun heading(held: HeldAction, appLabel: String?): String {
        val action = held.action
        if (action is Action.CallTool) return "Use ${action.tool} from ${action.server}?"
        val label = label(held)
        val title = held.facts.title
        val count = held.facts.attachmentCount
        return when {
            title != null && count != null ->
                "${items(count, held.facts.attachmentNoun)} to $title. $label ${if (count == 1) "it" else "them"}?"
            title != null && held.facts.composeText != null -> "$label this to $title?"
            title != null && appLabel != null -> "$label in $appLabel to $title?"
            title != null -> "$label to $title?"
            appLabel != null -> "$label in $appLabel?"
            else -> "$label?"
        }
    }

    /** The primary button: "Send to Alex" when the title is known, else "Send". */
    fun actionWord(held: HeldAction): String {
        val label = label(held)
        return held.facts.title?.let { "$label to $it" } ?: label
    }

    private val pastTense = linkedMapOf(
        "place order" to "Ordered", "order now" to "Ordered",
        "send" to "Sent", "post" to "Posted", "call" to "Called", "confirm" to "Confirmed", "pay" to "Paid",
        "buy" to "Bought", "delete" to "Deleted", "remove" to "Removed", "install" to "Installed",
        "submit" to "Submitted", "publish" to "Published", "transfer" to "Transferred", "checkout" to "Checked out",
        "purchase" to "Purchased", "use" to "Used", "share" to "Shared", "reply" to "Replied", "follow" to "Followed",
    )

    /** Past tense of a commit label for a receipt: "Send" -> "Sent", "SEND MESSAGE" -> "Sent"; unknown -> "Done". */
    fun receiptVerb(label: String?): String {
        if (label.isNullOrBlank()) return FALLBACK_RECEIPT
        val words = label.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        for ((phrase, past) in pastTense) {
            val parts = phrase.split(' ')
            if (words.windowed(parts.size).any { it == parts }) return past
        }
        return FALLBACK_RECEIPT
    }

    /** Receipt detail: "9 photos to Alex", "a message to Alex", "to Alex", or the app name. */
    fun receiptDetail(count: Int?, title: String?, appLabel: String?, noun: String? = null, hasText: Boolean = false): String = when {
        count != null && title != null -> "${items(count, noun)} to $title"
        hasText && title != null -> "a message to $title"
        title != null -> "to $title"
        else -> appLabel.orEmpty()
    }

    /** "3 photos", "1 photo", "2 items": [noun] is the plural ("photos"); null means [ITEMS]. */
    fun items(count: Int, noun: String? = null): String {
        val plural = noun?.takeIf { it.isNotBlank() } ?: ITEMS
        return if (count == 1) "1 ${plural.removeSuffix("s")}" else "$count $plural"
    }

    /** The first sentence of a model's finish text, at most [max] characters, ending with a full stop. */
    fun firstSentence(text: String?, max: Int = MAX_RESULT): String {
        val flat = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (flat.isEmpty()) return DONE
        val end = Regex("[.!?](\\s|$)").find(flat)?.range?.first
        var sentence = if (end != null) flat.substring(0, end + 1) else flat
        if (sentence.length > max) {
            val cut = sentence.take(max - 1)
            val space = cut.lastIndexOf(' ')
            sentence = (if (space > max / 2) cut.substring(0, space) else cut).trimEnd(',', ';', ':', ' ') + "."
        }
        return asSentence(sentence)
    }

    /** Adds a full stop to a one-line phrase; replaces an exclamation mark; leaves multi-line text alone. */
    fun asSentence(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || t.contains('\n')) return t
        if (t.endsWith("!")) return t.dropLast(1).trimEnd() + "."
        return if (t.last().isLetterOrDigit() || t.last() == ')' || t.last() == '"') "$t." else t
    }

    const val MODEL_UNREACHABLE = "I could not reach the model. Check the connection."
    const val MODEL_AUTH = "The model refused the sign-in. Sign in again in Set up."
    const val MODEL_LIMIT = "The model's usage limit is reached. Try again later."
    const val MODEL_SERVER = "The model service has a problem. Try again in a minute."
    const val MODEL_OTHER = "The model gave an error. Try again."

    /** The sentence for a typed model error (llm/ModelError), the same on the home page and the Model screen. */
    fun modelFailure(error: ai.opencyclon.llm.ModelError): String = when (error.kind) {
        ai.opencyclon.llm.ModelErrorKind.UNREACHABLE -> MODEL_UNREACHABLE
        ai.opencyclon.llm.ModelErrorKind.MODEL_NOT_FOUND -> error.model?.let {
            "This account cannot use the model $it. Choose another in Settings, Model."
        } ?: "This account cannot use the chosen model. Choose another in Settings, Model."
        ai.opencyclon.llm.ModelErrorKind.AUTH -> MODEL_AUTH
        ai.opencyclon.llm.ModelErrorKind.RATE_LIMIT -> MODEL_LIMIT
        ai.opencyclon.llm.ModelErrorKind.SERVER -> MODEL_SERVER
        ai.opencyclon.llm.ModelErrorKind.OTHER -> MODEL_OTHER
    }

    /** One sentence for a failed task, without error codes (UI.md section 6). */
    fun failure(reason: String?): String {
        val r = reason?.trim().orEmpty()
        val modelError = ai.opencyclon.llm.ModelError.find(r)
        return when {
            r.isEmpty() -> "I could not finish this task."
            modelError != null -> modelFailure(modelError)
            r.startsWith("LLM error", ignoreCase = true) || r.contains("API error", ignoreCase = true) -> MODEL_OTHER
            r.startsWith("Failed to capture screenshot") -> "I could not see the hidden screen. Nothing more was done."
            r.startsWith("Failed to create virtual display") -> "I could not start the hidden screen. Try again."
            r.startsWith(ai.opencyclon.gate.ApprovalGate.UNREADABLE_MESSAGE) -> "I could not read the app's screen, so I stopped."
            r.startsWith(ai.opencyclon.gate.ApprovalGate.PROTECTED_MESSAGE) -> "That app is protected. I do not operate it."
            r.length > MAX_RESULT || r.contains('\n') -> firstSentence(r)
            else -> asSentence(r)
        }
    }

    /** "2:41": the 12-hour clock without AM or PM, as in the reference video. */
    fun clock(at: Long): String = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(at))

    /** Template when triage gave no sentence: "<Sender> asked for something. I'm working on it." */
    fun triageFallback(sender: String?): String =
        "${sender?.trim()?.takeIf { it.isNotEmpty() } ?: "Someone"} asked for something. I'm working on it."

    // ── Lane X1: connection, protected app, secure screen, unreadable target, receipts ──

    /** The task ended because the privileged connection did not come back. */
    const val CONNECTION_LOST = "I lost the connection to this phone. Nothing more was sent."
    /** Under an approval whose target title could not be read (flow F5.14). */
    const val UNREADABLE_TARGET = "I can't read this screen, so I won't press it."
    /** The receipt verb of a task that only opened an app. */
    const val OPENED = "Opened"
    /** The name used when the app is not known. */
    const val THE_APP = "the app"

    /** The task reached an app on the protected list. */
    fun protectedApp(appName: String): String = "I do not open $appName. It is on your protected list."

    /** The hidden display showed only black: the app marked the screen secure. */
    fun secureScreen(appName: String): String = "I cannot read this screen in $appName, so I stopped."
}
