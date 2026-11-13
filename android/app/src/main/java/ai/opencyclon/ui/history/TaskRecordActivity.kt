package ai.opencyclon.ui.history

import ai.opencyclon.R
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.feed.HomeCommands
import ai.opencyclon.feed.ThreadItem
import ai.opencyclon.feed.ThreadKind
import ai.opencyclon.feed.ThreadState
import ai.opencyclon.miniapp.MiniAppActivity
import ai.opencyclon.ui.ControlPanelActivity
import ai.opencyclon.ui.theme.applySystemBarPadding
import ai.opencyclon.ui.theme.drawBehindSystemBars
import ai.opencyclon.ui.thread.ThreadAdapter
import ai.opencyclon.ui.thread.ThreadSpacing
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.Date

/**
 * The record of one task (F15.2): its rows drawn by the home thread's [ThreadAdapter], read-only, and live while
 * the task runs. A finished approval shows no buttons (the adapter's own rule); an open one is wired to the real
 * commands, so the record can approve too. Under each answered approval one Small line: "Approved · 2:41 PM" or
 * "Not sent · 2:41 PM". The adapter is not changed: the list is split after each answered approval, and each part
 * is its own wrap-content RecyclerView (with the home thread's spacing) inside one scroll view, the line between.
 */
class TaskRecordActivity : AppCompatActivity() {

    private lateinit var rowsView: LinearLayout
    private lateinit var gone: TextView
    private var commands: HomeCommands? = null
    private var taskId = 0L
    private var segments: List<Segment> = emptyList()
    private var parts: List<Pair<ThreadAdapter, TextView?>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        setContentView(R.layout.activity_task_record)
        taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        rowsView = findViewById(R.id.record_rows)
        gone = findViewById(R.id.record_gone)
        applySystemBarPadding(findViewById(R.id.record_root))
        findViewById<Button>(R.id.record_ask_again).setOnClickListener { askAgain() }

        if (!FeedLocator.isReady || taskId == 0L) {
            finish()
            return
        }
        val feed = FeedLocator.feed
        commands = FeedLocator.commands
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { feed.record(taskId).collect(::bind) }
        }
    }

    private fun askAgain() {
        commands?.askAgain(taskId)
        startActivity(ControlPanelActivity.homeIntent(this))
        finish()
    }

    // ── Rows ────────────────────────────────────────────────────────────

    /** Rows up to an answered approval (or the end), and the line under that approval. */
    private data class Segment(val items: List<ThreadItem>, val note: String?)

    private fun bind(items: List<ThreadItem>) {
        gone.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val next = split(items)
        val sameShape = next.size == segments.size &&
            next.zip(segments).all { (a, b) -> (a.note == null) == (b.note == null) }
        if (sameShape) {
            next.forEachIndexed { i, s ->
                parts[i].first.submitList(s.items)
                parts[i].second?.text = s.note
            }
        } else {
            rowsView.removeAllViews()
            parts = next.mapIndexed { i, s -> addPart(s, first = i == 0) }
        }
        segments = next
    }

    private fun addPart(segment: Segment, first: Boolean): Pair<ThreadAdapter, TextView?> {
        val adapter = threadAdapter().also { it.submitList(segment.items) }
        rowsView.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TaskRecordActivity)
            this.adapter = adapter
            addItemDecoration(ThreadSpacing(this@TaskRecordActivity))
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (!first) topMargin = resources.getDimensionPixelSize(R.dimen.gap_block)
        })
        val note = segment.note?.let { text ->
            TextView(this).apply {
                setTextAppearance(R.style.TextAppearance_App_Small)
                setTextColor(ContextCompat.getColor(context, R.color.ink_2))
                tag = TAG_NOTE
                this.text = text
            }.also {
                val margin = resources.getDimensionPixelSize(R.dimen.margin_page)
                rowsView.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = margin
                    marginEnd = margin
                    topMargin = (6 * resources.displayMetrics.density).toInt()
                })
            }
        }
        return adapter to note
    }

    private fun split(items: List<ThreadItem>): List<Segment> {
        val out = mutableListOf<Segment>()
        var current = mutableListOf<ThreadItem>()
        items.forEachIndexed { i, item ->
            current += item
            noteFor(item, items.getOrNull(i + 1))?.let {
                out += Segment(current, it)
                current = mutableListOf()
            }
        }
        if (current.isNotEmpty() || out.isEmpty()) out += Segment(current, null)
        return out
    }

    /** "Approved · 2:41 PM" or "Not sent · 2:41 PM" under an answered approval; null for any other row. */
    private fun noteFor(item: ThreadItem, next: ThreadItem?): String? {
        if (item.kind != ThreadKind.APPROVAL) return null
        // ponytail: a row stores only when it was written; the next row (the receipt or the phone's sentence)
        // stands in for the moment of the answer.
        val time = DateFormat.getTimeFormat(this).format(Date(next?.at ?: item.at))
        return when (item.state) {
            ThreadState.APPROVED -> getString(R.string.hist_approved_at, time)
            ThreadState.REJECTED, ThreadState.EXPIRED -> getString(R.string.hist_not_sent_at, time)
            else -> null
        }
    }

    private fun threadAdapter() = ThreadAdapter().apply {
        onApprove = { id ->
            val c = commands
            if (c != null) lifecycleScope.launch {
                if (!c.approve(id)) Toast.makeText(this@TaskRecordActivity, R.string.home_unlock_to_approve, Toast.LENGTH_SHORT).show()
            }
        }
        onNotNow = { id -> commands?.reject(id) }
        onOpen = { item ->
            val appId = item.openAppId
            val id = item.taskId
            when {
                appId != null -> startActivity(MiniAppActivity.intent(this@TaskRecordActivity, appId))
                item.kind == ThreadKind.APPROVAL && id != null && item.state == ThreadState.OPEN ->
                    commands?.openLive(this@TaskRecordActivity, id)
            }
        }
    }

    @VisibleForTesting internal fun rowsView(): LinearLayout = rowsView

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val TAG_NOTE = "record_note"

        fun intent(context: Context, taskId: Long): Intent =
            Intent(context, TaskRecordActivity::class.java).putExtra(EXTRA_TASK_ID, taskId)
    }
}
