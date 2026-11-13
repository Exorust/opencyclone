package ai.opencyclon.ui.history

import ai.opencyclon.R
import ai.opencyclon.feed.Feed
import ai.opencyclon.feed.HistoryEntry
import ai.opencyclon.feed.HistoryStatus
import ai.opencyclon.feed.HomeCommands
import ai.opencyclon.ui.screens.MoreActivity
import ai.opencyclon.ui.theme.applySystemBarPadding
import ai.opencyclon.ui.widget.AppIcons
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The history page (F15.1, F15.3, F15.4, F15.6), the left page of the home pager: every task, newest first,
 * grouped by day, with search. A tap opens the record; a press and hold offers "Ask again" and "Delete", or
 * "Stop" for a task that has not ended.
 */
class HistoryPageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    /** "Ask again" was chosen (the sheet): the host submits it and shows the home page. */
    var onAskAgain: (HistoryEntry) -> Unit = {}

    private val search: EditText
    private val list: RecyclerView
    private val empty: TextView
    private val adapter = Rows()
    private val query = MutableStateFlow("")
    private var commands: HomeCommands? = null
    private var connected = false
    private var entries: List<HistoryEntry> = emptyList()

    @VisibleForTesting internal var lastDialog: Dialog? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.page))
        // The page takes focus itself, so the search field gets it only when tapped.
        isFocusableInTouchMode = true
        LayoutInflater.from(context).inflate(R.layout.page_history, this, true)
        search = findViewById(R.id.hist_search)
        list = findViewById(R.id.hist_list)
        empty = findViewById(R.id.hist_empty)
        search.background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.surface_2))
            cornerRadius = resources.getDimension(R.dimen.radius_pill)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { query.value = s?.toString().orEmpty().trim() }
        })
        findViewById<View>(R.id.hist_menu).setOnClickListener { context.startActivity(MoreActivity.intent(context)) }
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        applySystemBarPadding(this)
    }

    /** Starts listing the feed's history while [owner] is started. Safe to call again. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun connect(feed: Feed, commands: HomeCommands, owner: LifecycleOwner) {
        this.commands = commands
        if (connected) return
        connected = true
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                query.flatMapLatest { feed.history(it) }.collect(::bind)
            }
        }
    }

    /** The page is no longer in front: the keyboard goes away. */
    fun onHidden() {
        if (search.hasFocus()) {
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
            requestFocus()
        }
    }

    private fun bind(list: List<HistoryEntry>) {
        entries = list
        val now = clock()
        val rows = mutableListOf<Row>()
        var group = -1
        for (e in list) {
            val g = groupOf(e.at, now)
            if (g != group) {
                rows += Row.Group(context.getString(GROUP_TITLES[g]))
                group = g
            }
            rows += Row.Entry(e, g)
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        val blank = query.value.isEmpty()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (blank) R.string.hist_empty else R.string.hist_no_match)
    }

    // ── Sheet ───────────────────────────────────────────────────────────

    private fun showSheet(entry: HistoryEntry) {
        val sheet = BottomSheetDialog(context)
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, dp(24))
        }
        fun option(@StringRes label: Int, action: () -> Unit) {
            body.addView(TextView(context).apply {
                setTextAppearance(R.style.TextAppearance_App_Body)
                setTextColor(ContextCompat.getColor(context, R.color.ink))
                setText(label)
                minHeight = dp(56)
                gravity = android.view.Gravity.CENTER_VERTICAL
                val m = resources.getDimensionPixelSize(R.dimen.margin_page)
                setPadding(m, 0, m, 0)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { sheet.dismiss(); action() }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        option(R.string.hist_ask_again) { onAskAgain(entry) }
        val ended = entry.status != HistoryStatus.RUNNING && entry.status != HistoryStatus.WAITING
        when {
            ended -> option(R.string.hist_delete) { confirmDelete(entry) }
            entry.taskId > 0 -> option(R.string.hist_stop) { commands?.stop(entry.taskId) }
        }
        sheet.setContentView(body)
        lastDialog = sheet
        sheet.show()
    }

    private fun confirmDelete(entry: HistoryEntry) {
        lastDialog = AlertDialog.Builder(context)
            .setTitle(R.string.hist_delete_confirm)
            .setMessage(R.string.hist_delete_detail)
            .setNegativeButton(R.string.hist_cancel, null)
            .setPositiveButton(R.string.hist_delete) { _, _ -> commands?.deleteTask(entry.taskId) }
            .show()
    }

    // ── Rows ────────────────────────────────────────────────────────────

    private sealed class Row {
        data class Group(val title: String) : Row()
        data class Entry(val entry: HistoryEntry, val group: Int) : Row()
    }

    private inner class Rows : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()

        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Group) TYPE_GROUP else TYPE_ENTRY

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val layout = if (viewType == TYPE_GROUP) R.layout.item_history_group else R.layout.item_history_row
            return object : RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Group -> (holder.itemView as TextView).text = row.title
                is Row.Entry -> bindEntry(holder.itemView, row)
            }
        }
    }

    private fun bindEntry(view: View, row: Row.Entry) {
        val e = row.entry
        val title = view.findViewById<TextView>(R.id.hist_row_title)
        title.text = highlighted(e.title, e.match, title.typeface)
        view.findViewById<TextView>(R.id.hist_row_detail).text =
            context.getString(R.string.hist_detail_format, timeText(e.at, row.group), context.getString(statusWord(e.status)))
        val icons = view.findViewById<LinearLayout>(R.id.hist_row_icons)
        icons.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.icon_glyph)
        e.packages.take(3).forEachIndexed { i, pkg ->
            icons.addView(ImageView(context).also { AppIcons.into(it, pkg) },
                LayoutParams(size, size).apply { if (i > 0) marginStart = dp(4) })
        }
        view.setOnClickListener { context.startActivity(TaskRecordActivity.intent(context, e.taskId)) }
        view.setOnLongClickListener { showSheet(e); true }
    }

    private fun timeText(at: Long, group: Int): CharSequence {
        val d = Date(at)
        val time = DateFormat.getTimeFormat(context).format(d)
        return when (group) {
            TODAY, YESTERDAY -> time
            THIS_WEEK -> "${DateFormat.format("EEE", d)} $time"
            else -> DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"), d)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @VisibleForTesting internal fun searchField(): EditText = search
    @VisibleForTesting internal fun emptyText(): TextView = empty
    @VisibleForTesting internal fun listView(): RecyclerView = list

    companion object {
        const val TODAY = 0
        const val YESTERDAY = 1
        const val THIS_WEEK = 2
        const val OLDER = 3
        private const val TYPE_GROUP = 0
        private const val TYPE_ENTRY = 1
        private val GROUP_TITLES = intArrayOf(R.string.hist_today, R.string.hist_yesterday, R.string.hist_this_week, R.string.hist_older)

        /** Clock of the day groups (tests replace it). */
        @VisibleForTesting var clock: () -> Long = System::currentTimeMillis

        /** The day group of [at] seen at [now]: TODAY, YESTERDAY, THIS_WEEK (since the week's first day), OLDER. */
        fun groupOf(at: Long, now: Long): Int {
            val today = midnight(now, 0)
            val yesterday = midnight(now, -1)
            val week = Calendar.getInstance().apply {
                timeInMillis = today
                while (get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) add(Calendar.DAY_OF_MONTH, -1)
            }.timeInMillis
            return when {
                at >= today -> TODAY
                at >= yesterday -> YESTERDAY
                at >= week -> THIS_WEEK
                else -> OLDER
            }
        }

        private fun midnight(at: Long, days: Int): Long = Calendar.getInstance().apply {
            timeInMillis = at
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, days)
        }.timeInMillis

        @StringRes fun statusWord(status: HistoryStatus): Int = when (status) {
            HistoryStatus.DONE -> R.string.hist_status_done
            HistoryStatus.NOT_SENT -> R.string.hist_status_not_sent
            HistoryStatus.FAILED -> R.string.hist_status_failed
            HistoryStatus.STOPPED -> R.string.hist_status_stopped
            HistoryStatus.RUNNING -> R.string.hist_status_running
            HistoryStatus.WAITING -> R.string.hist_status_waiting
        }

        /** [title] with the characters of [match] in weight 600. */
        fun highlighted(title: String, match: IntRange?, base: Typeface?): CharSequence {
            val m = match?.takeIf { it.first >= 0 && it.last < title.length && !it.isEmpty() } ?: return title
            return SpannableString(title).apply {
                setSpan(TypefaceSpan(Typeface.create(base ?: Typeface.DEFAULT, 600, false)), m.first, m.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
