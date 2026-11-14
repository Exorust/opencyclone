package ai.opencyclone.gate

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import ai.opencyclone.R
import ai.opencyclone.tasks.ProactiveRuntime
import ai.opencyclone.ui.screens.PlainScreen

/** Shows the held action and takes a correction for the agent, in the plain look. */
class EditActivity : Activity() {
    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SUMMARY = "summary"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val summary = intent.getStringExtra(EXTRA_SUMMARY)?.takeIf { it.isNotBlank() }
        val screen = PlainScreen.install(this, getString(R.string.screens_edit_title), summary)
        val input = screen.fieldRow(
            getString(R.string.screens_edit_label),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        ).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            requestFocus()
        }
        screen.primary(getString(R.string.screens_save)) {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@primary
            val runtime = ProactiveRuntime.init(this)
            val accepted = runtime.session.edit(taskId, text)
            // The owner's words go into the thread, as when they are typed on the home page.
            if (accepted) runtime.recordOwnerWords(text)
            if (!accepted) Toast.makeText(this, getString(R.string.screens_edit_gone), Toast.LENGTH_SHORT).show()
            ApprovalCard.cancel(this, taskId)
            finish()
        }
        // Cancel leaves the request held; the approval card stays.
        screen.pale(getString(R.string.screens_cancel)) { finish() }
    }
}
