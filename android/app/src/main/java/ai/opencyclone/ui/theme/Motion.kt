package ai.opencyclone.ui.theme

import android.view.View

/** Motion values, UI.md section 3.5 with the corrections of section 10. */
object Motion {
    /** A card or pill enters with a fade only, no upward move. */
    const val ENTER_MS = 250L
    /** A thing that leaves fades out. */
    const val LEAVE_MS = 160L
    /** Cross-fade between two washes. */
    const val WASH_MS = 600
    /** Word reveal: delay between words in a status sentence or question. */
    const val WORD_MS = 100L
    /** Word reveal: delay between words in a long result sentence. */
    const val WORD_LONG_SENTENCE_MS = 70L
    /** Word reveal: time for one word to go from ink-3 to its colour. */
    const val WORD_COLOUR_MS = 250L
}

/** Fades the view in over [Motion.ENTER_MS]. */
fun View.fadeIn() {
    animate().cancel()
    alpha = 0f
    visibility = View.VISIBLE
    animate().alpha(1f).setDuration(Motion.ENTER_MS).start()
}

/** Fades the view out over [Motion.LEAVE_MS], then sets it to GONE. */
fun View.fadeOut() {
    animate().cancel()
    animate().alpha(0f).setDuration(Motion.LEAVE_MS).withEndAction { visibility = View.GONE }.start()
}
