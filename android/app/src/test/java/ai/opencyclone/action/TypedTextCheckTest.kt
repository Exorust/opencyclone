package ai.opencyclone.action

import ai.opencyclone.accessibility.FocusedField
import ai.opencyclone.action.TypedTextCheck.Verdict
import android.text.InputType
import org.junit.Assert.*
import org.junit.Test

class TypedTextCheckTest {
    private fun field(text: String?, password: Boolean = false, hint: Boolean = false, type: Int = InputType.TYPE_CLASS_TEXT) =
        FocusedField(text, password, hint, type)

    @Test fun `text that arrived is found`() {
        assertEquals(Verdict.ARRIVED, TypedTextCheck.verify("hello", field("hello")))
        assertEquals(Verdict.ARRIVED, TypedTextCheck.verify("  hello ", field("Say hello there")))
    }

    @Test fun `text that did not arrive is missing`() {
        assertEquals(Verdict.MISSING, TypedTextCheck.verify("hello", field("")))
        assertEquals(Verdict.MISSING, TypedTextCheck.verify("hello", field(null)))
        assertEquals(Verdict.MISSING, TypedTextCheck.verify("hello", field("Message", hint = true)))
        assertEquals(Verdict.MISSING, TypedTextCheck.verify("hello", field("hel")))
        assertEquals("The text did not arrive in the field.", TypedTextCheck.MISSING_FEEDBACK)
    }

    @Test fun `fields that hide or format their text cannot be checked`() {
        assertEquals(Verdict.CANNOT_CHECK, TypedTextCheck.verify("secret", field("••••••", password = true)))
        assertEquals(Verdict.CANNOT_CHECK, TypedTextCheck.verify("secret",
            field("••••", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertEquals(Verdict.CANNOT_CHECK, TypedTextCheck.verify("4155550100x", field("(415) 555-01", type = InputType.TYPE_CLASS_PHONE)))
        // A formatted number whose digits all arrived counts as arrived.
        assertEquals(Verdict.ARRIVED, TypedTextCheck.verify("4155550100", field("(415) 555-0100", type = InputType.TYPE_CLASS_PHONE)))
    }

    @Test fun `no focused field or a typed line break cannot be checked`() {
        assertEquals(Verdict.CANNOT_CHECK, TypedTextCheck.verify("hello", null))
        assertEquals(Verdict.CANNOT_CHECK, TypedTextCheck.verify("hello\n", field("")))
    }
}
