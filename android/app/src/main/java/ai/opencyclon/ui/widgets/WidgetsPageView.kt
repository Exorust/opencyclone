package ai.opencyclon.ui.widgets

import ai.opencyclon.R
import ai.opencyclon.ui.theme.applySystemBarPadding
import ai.opencyclon.widgets.WidgetBridge
import ai.opencyclon.widgets.WidgetEntity
import ai.opencyclon.widgets.Widgets
import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Widgets page (F16), the page at the right of the home page. The pager (lane H1) creates it by name with
 * the constructor `(Context)` and may call [setActive] when the page comes into or leaves view; without that call
 * the page follows its own visibility. Widget WebViews exist only while the page is active (F16.6).
 */
class WidgetsPageView(context: Context) : FrameLayout(context) {

    /** Brings the home page to the front (set by the pager); without it an intent to the home activity is used. */
    var onShowHome: (() -> Unit)? = null

    private val root: View = LayoutInflater.from(context).inflate(R.layout.page_widgets, this, false)
    private val empty: TextView = root.findViewById(R.id.widgets_empty)
    private val example: TextView = root.findViewById(R.id.widgets_example)
    private val grid: RecyclerView = root.findViewById(R.id.widgets_grid)
    private val adapter = CardAdapter()
    private val log = WidgetBridge.logger(context)
    private val gap = resources.getDimensionPixelSize(R.dimen.gap_buttons)

    private var scope: CoroutineScope? = null
    private var refreshJob: Job? = null
    private var requested: Boolean? = null
    private var aggregatedVisible = false
    private var active = false

    private var widgets: List<WidgetEntity> = emptyList()
    private val html = HashMap<String, String>()           // key(widget) -> file text
    private val stopped = HashSet<String>()                // key(widget) of widgets that threw or failed to load
    private val hosts = HashMap<Long, Pair<Holder, WidgetHost>>()

    init {
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applySystemBarPadding(root)
        val margin = resources.getDimensionPixelSize(R.dimen.margin_page)
        grid.setPadding(margin - gap / 2, grid.paddingTop, margin - gap / 2, grid.paddingBottom)
        grid.layoutManager = GridLayoutManager(context, 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = widgets.getOrNull(position)?.widgetSize?.span ?: 2
            }
        }
        grid.adapter = adapter
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(gap / 2, 0, gap / 2, gap)
            }
        })
        ItemTouchHelper(DragCallback()).attachToRecyclerView(grid)
        render()
    }

    /** The pager's call: true when this page is the one in view. */
    fun setActive(on: Boolean) {
        requested = on
        updateActive()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        updateActive()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val scope = MainScope().also { this.scope = it }
        if (Widgets.ready(context)) {
            scope.launch {
                Widgets.store.observe().collect { list ->
                    val texts = withContext(Dispatchers.IO) {
                        list.associate { key(it) to (html[key(it)] ?: Widgets.store.html(it)) }
                    }
                    html.clear()
                    texts.forEach { (k, v) -> if (v != null) html[k] = v }
                    show(list)
                }
            }
        }
        updateActive()
    }

    override fun onDetachedFromWindow() {
        setRunning(false)
        active = false
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private fun updateActive() {
        val want = isAttachedToWindow && (requested ?: aggregatedVisible)
        if (want == active) return
        active = want
        setRunning(want)
    }

    /** Loads the cards (and refreshes them at most once a minute), or destroys every WebView. */
    private fun setRunning(on: Boolean) {
        refreshJob?.cancel()
        refreshJob = null
        if (!on) {
            hosts.values.forEach { it.second.destroy() }
            hosts.clear()
            return
        }
        adapter.notifyDataSetChanged()
        refreshJob = scope?.launch {
            while (isActive) {
                delay(REFRESH_MS)
                hosts.forEach { (id, pair) ->
                    log("widget $id refresh")
                    pair.second.refresh()
                }
            }
        }
    }

    private fun show(list: List<WidgetEntity>) {
        val sameOrder = list.map(::key) == widgets.map(::key)
        widgets = list
        stopped.retainAll(list.map(::key).toSet())
        if (!sameOrder) adapter.notifyDataSetChanged()
        render()
    }

    private fun render() {
        val none = widgets.isEmpty()
        empty.visibility = if (none) View.VISIBLE else View.GONE
        example.visibility = if (none) View.VISIBLE else View.GONE
        grid.visibility = if (none) View.GONE else View.VISIBLE
    }

    private fun key(widget: WidgetEntity) = "${widget.id}:${widget.version}"

    private fun bindHost(holder: Holder, widget: WidgetEntity) {
        hosts[widget.id]?.let { (owner, host) -> if (owner !== holder) { host.destroy(); hosts.remove(widget.id) } }
        holder.release()
        val text = html[key(widget)]
        val isStopped = key(widget) in stopped
        holder.stoppedText.visibility = if (isStopped || (text == null && widgets.isNotEmpty())) View.VISIBLE else View.GONE
        val scope = scope ?: return
        if (!active || isStopped || text == null) return
        val host = WidgetHost(holder.web, "w${widget.id}v${widget.version}", text, widget.sourceList, scope,
            onStopped = { markStopped(widget) },
            onAsk = { words -> WidgetHost.sendAsOwner(context, words, onShowHome) }, size = widget.widgetSize)
        holder.host = host
        holder.boundId = widget.id
        hosts[widget.id] = holder to host
        log("widget ${widget.id} loaded")
    }

    @VisibleForTesting internal fun markStopped(widget: WidgetEntity) {
        stopped += key(widget)
        hosts.remove(widget.id)?.second?.destroy()
        val index = widgets.indexOfFirst { it.id == widget.id }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    private fun rebuild(widget: WidgetEntity) {
        WidgetHost.sendAsOwner(context, context.getString(R.string.widgets_rebuild_request, widget.name), onShowHome)
    }

    // ── Press and hold: move, or the sheet ──────────────────────────────

    private fun showSheet(widget: WidgetEntity) {
        val dialog = BottomSheetDialog(context)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.margin_page)
            setPadding(pad, pad, pad, pad)
        }
        fun row(text: Int, action: () -> Unit) = rows.addView(TextView(context).apply {
            setTextAppearance(R.style.TextAppearance_App_Body)
            setText(text)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { dialog.dismiss(); action() }
        })
        row(R.string.widgets_change) { scope?.launch { Widgets.flow.openSaved(widget.id) } }
        row(R.string.widgets_remove) { confirmRemove(widget) }
        dialog.setContentView(rows)
        dialog.show()
    }

    private fun confirmRemove(widget: WidgetEntity) {
        MaterialAlertDialogBuilder(context)
            .setMessage(context.getString(R.string.widgets_remove_question, widget.name))
            .setPositiveButton(R.string.widgets_remove) { _, _ -> remove(widget.id) }
            .setNegativeButton(R.string.ui_not_now, null)
            .show()
    }

    @VisibleForTesting internal fun remove(id: Long) {
        scope?.launch { Widgets.store.remove(id) }
    }

    private inner class DragCallback : ItemTouchHelper.Callback() {
        private var moved = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            move(viewHolder.adapterPosition, target.adapterPosition)
            moved = true
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) moved = false
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (moved) saveOrder() else widgets.getOrNull(viewHolder.adapterPosition)?.let(::showSheet)
            moved = false
        }
    }

    /** Moves the card at [from] to [to] on the page; [saveOrder] stores it. */
    @VisibleForTesting internal fun move(from: Int, to: Int) {
        if (from !in widgets.indices || to !in widgets.indices || from == to) return
        widgets = widgets.toMutableList().apply { add(to, removeAt(from)) }
        adapter.notifyItemMoved(from, to)
    }

    @VisibleForTesting internal fun saveOrder() {
        val ids = widgets.map { it.id }
        scope?.launch { Widgets.store.reorder(ids) }
    }

    @VisibleForTesting internal fun liveHosts(): Int = hosts.size
    @VisibleForTesting internal fun isRunning(): Boolean = active
    @VisibleForTesting internal fun grid(): RecyclerView = grid
    @VisibleForTesting internal fun spanOf(position: Int): Int = widgets.getOrNull(position)?.widgetSize?.span ?: 2

    // ── Cards ────────────────────────────────────────────────────────────

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: FrameLayout = view.findViewById(R.id.widget_card)
        val web: FrameLayout = view.findViewById(R.id.widget_web)
        val stoppedText: TextView = view.findViewById(R.id.widget_stopped)
        var host: WidgetHost? = null
        var boundId: Long? = null

        fun release() {
            host?.let { h ->
                h.destroy()
                boundId?.let { id -> if (hosts[id]?.second === h) hosts.remove(id) }
            }
            host = null
            boundId = null
        }
    }

    private inner class CardAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = widgets.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_widget_card, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val widget = widgets[position]
            holder.card.layoutParams = holder.card.layoutParams.apply { height = WidgetHost.cardHeight(context, widget.widgetSize) }
            holder.card.contentDescription = context.getString(R.string.widgets_card_desc, widget.name)
            holder.stoppedText.setOnClickListener { rebuild(widget) }
            bindHost(holder, widget)
        }

        override fun onViewRecycled(holder: Holder) = holder.release()
    }

    companion object {
        const val REFRESH_MS = 60_000L
    }
}
