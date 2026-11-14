package ai.opencyclone.action

import ai.opencyclone.accessibility.FocusedField
import android.text.InputType

/** Reads typed text back from the focused field (F18.3). Pure. */
object TypedTextCheck {
    enum class Verdict { ARRIVED, MISSING, CANNOT_CHECK }

    const val MISSING_FEEDBACK = "The text did not arrive in the field."

    private const val CLASS_MASK = 0x0000000f
    private val FORMATTING_CLASSES = setOf(InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME)
    private val PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
    )

    /**
     * ARRIVED when the field contains the typed text (trimmed; for formatted input, the letters and digits).
     * CANNOT_CHECK without a focused field, for a typed line break (it submits and may clear the field), for fields
     * that hide their text, and for fields that format input (phone, number, date). Otherwise MISSING.
     */
    fun verify(typed: String, field: FocusedField?): Verdict {
        val want = typed.trim()
        if (want.isEmpty() || field == null || typed.any { it == '\n' || it == '\r' }) return Verdict.CANNOT_CHECK
        if (field.password || field.inputType and (CLASS_MASK or 0x00000ff0) in PASSWORD_VARIATIONS) return Verdict.CANNOT_CHECK
        val have = if (field.showingHint) "" else field.text.orEmpty()
        if (have.contains(want)) return Verdict.ARRIVED
        val wantPlain = want.filter { it.isLetterOrDigit() }
        if (wantPlain.isNotEmpty() && have.filter { it.isLetterOrDigit() }.contains(wantPlain)) return Verdict.ARRIVED
        if (field.inputType and CLASS_MASK in FORMATTING_CLASSES) return Verdict.CANNOT_CHECK
        return Verdict.MISSING
    }
}
