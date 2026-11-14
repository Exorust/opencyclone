package ai.opencyclone.gate

data class ElementInfo(
    val text: String?, val description: String?, val idName: String?,
    val className: String?, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val editable: Boolean
)

/** A rectangle in display pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * What the gate read from one window at hold time.
 * [attachmentCount] and [attachmentNoun] ("photos", "files", "items") are null together when the count is unreadable.
 * [composeText] is the text of the field that will be sent, at most [MAX_COMPOSE] characters, null when none.
 * [attachmentBounds] are the selected items on screen (for crops); the re-check on Approve does not compare them.
 */
data class ScreenFacts(
    val topPackage: String?, val title: String?, val attachmentCount: Int?,
    val composeText: String? = null,
    val attachmentNoun: String? = null,
    val attachmentBounds: List<Box> = emptyList(),
) {
    /** The facts the re-check on Approve compares (decision D9): everything but the item positions. */
    fun forRecheck(): ScreenFacts = if (attachmentBounds.isEmpty()) this else copy(attachmentBounds = emptyList())

    companion object {
        const val MAX_COMPOSE = 500

        /** [text] cut to [MAX_COMPOSE] characters with an ellipsis. */
        fun capCompose(text: String): String =
            if (text.length <= MAX_COMPOSE) text else text.take(MAX_COMPOSE - 1) + "…"
    }
}

/** Reads only the requested display; unavailable windows produce Unreadable. */
interface ScreenReader {
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        object Unreadable : Result<Nothing>()
    }
    fun elementAt(displayId: Int, xPx: Int, yPx: Int): Result<ElementInfo?>
    fun focusedElement(displayId: Int): Result<ElementInfo?>
    fun screenFacts(displayId: Int): Result<ScreenFacts>
    /** Facts of the window a tap at this point lands in (for example a launcher taskbar), else of the top app window. */
    fun screenFactsAt(displayId: Int, xPx: Int, yPx: Int): Result<ScreenFacts> = screenFacts(displayId)
}
