package ai.opencyclon.ui.home

import ai.opencyclon.R
import ai.opencyclon.ui.theme.fadeIn
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * The type input of UI.md section 9 item 7: one text field in a `surface` pill above the keyboard with the
 * primary button "Send". It is in the view tree only while open, so the idle page has no text box.
 */
class TypeInput(private val root: ConstraintLayout, private val onSend: (String) -> Unit) {

    private var view: View? = null

    val isOpen: Boolean get() = view != null

    /** The field while open (tests). */
    val field: EditText? get() = view?.findViewById(R.id.type_field)

    /** Inflates the input at the bottom of the page, [bottomMargin] above the bottom edge, and shows the keyboard. */
    fun open(bottomMargin: Int) {
        if (view != null) return
        val v = LayoutInflater.from(root.context).inflate(R.layout.view_type_input, root, false)
        val margin = root.resources.getDimensionPixelSize(R.dimen.margin_page)
        v.layoutParams = ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            marginStart = margin
            marginEnd = margin
            this.bottomMargin = bottomMargin
        }
        val edit = v.findViewById<EditText>(R.id.type_field)
        v.findViewById<Button>(R.id.type_send).setOnClickListener { send() }
        edit.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) { send(); true } else false
        }
        root.addView(v)
        view = v
        v.fadeIn()
        edit.requestFocus()
        edit.post { imm()?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
    }

    /** Keeps the input [bottomMargin] above the bottom edge (it follows the keyboard). */
    fun setBottomMargin(bottomMargin: Int) {
        val v = view ?: return
        val lp = v.layoutParams as ConstraintLayout.LayoutParams
        if (lp.bottomMargin == bottomMargin) return
        lp.bottomMargin = bottomMargin
        v.layoutParams = lp
    }

    /** Hides the keyboard and removes the input from the view tree. */
    fun close() {
        val v = view ?: return
        view = null
        imm()?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
        root.removeView(v)
    }

    private fun send() {
        val text = field?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        field?.text?.clear()
        close()
        onSend(text)
    }

    private fun imm(): InputMethodManager? =
        root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
}
