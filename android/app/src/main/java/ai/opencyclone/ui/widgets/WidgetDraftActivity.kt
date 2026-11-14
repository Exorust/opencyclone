package ai.opencyclone.ui.widgets

import ai.opencyclone.R
import ai.opencyclone.ui.theme.applySystemBarPadding
import ai.opencyclone.ui.theme.drawBehindSystemBars
import ai.opencyclone.widgets.DraftPage
import ai.opencyclone.widgets.WidgetDraft
import ai.opencyclone.widgets.Widgets
import android.os.Bundle
import android.view.View
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The draft page (F16.2 steps 3 to 7): the widget at its real size with live data, "Draft. Not saved yet.",
 * a text field for changes, "Discard" and "Save". Leaving without saving deletes the draft.
 */
class WidgetDraftActivity : AppCompatActivity() {
    private lateinit var frame: FrameLayout
    private lateinit var hint: TextView
    private lateinit var confirm: View
    private lateinit var confirmText: TextView
    private lateinit var text: EditText
    private lateinit var save: Button
    private lateinit var discard: Button
    private var card: FrameLayout? = null
    private var host: WidgetHost? = null
    private var shownVersion = -1
    private var stopped = false
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.activity_widget_draft)
        applySystemBarPadding(findViewById(R.id.draft_root))
        frame = findViewById(R.id.draft_frame)
        hint = findViewById(R.id.draft_hint)
        confirm = findViewById(R.id.draft_confirm)
        confirmText = findViewById(R.id.draft_confirm_text)
        text = findViewById(R.id.draft_text)
        save = findViewById(R.id.draft_save)
        discard = findViewById(R.id.draft_discard)
        if (!Widgets.ready(this) || Widgets.flow.page.value.draft == null) {
            Toast.makeText(this, R.string.widgets_no_draft, Toast.LENGTH_SHORT).show()
            done = true
            finish()
            return
        }
        val flow = Widgets.flow
        flow.pageOpen = true
        findViewById<ImageButton>(R.id.draft_send).setOnClickListener { send() }
        text.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_SEND).also { if (it) send() } }
        findViewById<Button>(R.id.draft_confirm_yes).setOnClickListener { flow.answerOnPage(true) }
        findViewById<Button>(R.id.draft_confirm_no).setOnClickListener { flow.answerOnPage(false) }
        save.setOnClickListener { onSave() }
        discard.setOnClickListener { onDiscard() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                MaterialAlertDialogBuilder(this@WidgetDraftActivity)
                    .setMessage(R.string.widgets_discard_question)
                    .setPositiveButton(R.string.widgets_discard) { _, _ -> onDiscard() }
                    .setNegativeButton(R.string.widgets_keep, null)
                    .show()
            }
        })
        lifecycleScope.launch { flow.page.collect(::render) }
    }

    private fun send() {
        val words = text.text.toString().trim()
        if (words.isEmpty() || Widgets.flow.page.value.busy) return
        text.setText("")
        Widgets.flow.changeDraft(words)
    }

    private fun render(state: DraftPage) {
        val draft = state.draft
        if (draft == null) {
            if (!done) { done = true; finish() }
            return
        }
        if (state.version != shownVersion) {
            shownVersion = state.version
            stopped = false
            lifecycleScope.launch { show(draft) }
        }
        hint.text = when {
            state.busy -> getString(R.string.widgets_changing)
            state.message != null -> state.message
            stopped -> getString(R.string.widgets_stopped)
            else -> getString(R.string.widgets_draft_hint)
        }
        confirm.visibility = if (state.confirm != null) View.VISIBLE else View.GONE
        confirmText.text = state.confirm
        val idle = !state.busy && state.confirm == null
        save.isEnabled = idle
        discard.isEnabled = idle
        text.isEnabled = idle
    }

    private suspend fun show(draft: WidgetDraft) {
        val html = withContext(Dispatchers.IO) { runCatching { draft.file.readText(Charsets.UTF_8) }.getOrNull() } ?: return
        host?.destroy()
        val content = frame.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - 2 * resources.getDimensionPixelSize(R.dimen.margin_page))
        val gap = resources.getDimensionPixelSize(R.dimen.gap_buttons)
        val box = card ?: (LayoutInflater.from(this).inflate(R.layout.item_widget_card, frame, false) as FrameLayout)
            .also { frame.addView(it); card = it }
        box.layoutParams = FrameLayout.LayoutParams(WidgetHost.cardWidth(draft.size, content, gap), WidgetHost.cardHeight(this, draft.size))
        host = WidgetHost(box.findViewById(R.id.widget_web), "draft${draft.number}", html, draft.sources, lifecycleScope,
            onStopped = { stopped = true; render(Widgets.flow.page.value) },
            onAsk = { words -> WidgetHost.sendAsOwner(this, words, null) }, size = draft.size)
    }

    private fun onSave() {
        lifecycleScope.launch {
            Widgets.flow.saveDraft() ?: return@launch
            done = true
            startActivity(WidgetHost.homeIntent(this@WidgetDraftActivity, WidgetHost.PAGE_WIDGETS))
            finish()
        }
    }

    private fun onDiscard() {
        lifecycleScope.launch {
            Widgets.flow.discardDraft()
            done = true
            finish()
        }
    }

    override fun onDestroy() {
        host?.destroy()
        host = null
        if (Widgets.isReady) {
            Widgets.flow.pageOpen = false
            // Leaving the page in any other way (swiped away from recents) deletes the draft too.
            if (isFinishing && !done) Widgets.flow.discardLater()
        }
        super.onDestroy()
    }
}
