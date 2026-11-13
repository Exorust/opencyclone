package ai.opencyclon.ui.thread

import ai.opencyclon.R
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_APPROVAL
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_HIDDEN
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_NOTIFICATION_QUIET
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_QUESTION
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_RECEIPT
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_RESULT_TEXT
import ai.opencyclon.ui.thread.ThreadAdapter.Companion.TYPE_SENTENCE
import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.annotation.DimenRes
import androidx.recyclerview.widget.RecyclerView

/**
 * Space in today's thread (UI.md sections 3.4 and 10): page margin 24 dp at the left and right,
 * 24 dp between blocks, 16 dp between a heading and its content, 10 dp between receipts,
 * 8 dp between quiet cards. Collapsed rows take no space. Use with [ThreadAdapter].
 */
class ThreadSpacing(context: Context) : RecyclerView.ItemDecoration() {
    private val res = context.resources
    private val margin = res.getDimensionPixelSize(R.dimen.margin_page)

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter as? ThreadAdapter ?: return
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION || position >= adapter.itemCount) return
        outRect.left = margin
        outRect.right = margin
        val type = adapter.getItemViewType(position)
        if (type == TYPE_HIDDEN) return
        var prev = position - 1
        while (prev >= 0 && adapter.getItemViewType(prev) == TYPE_HIDDEN) prev--
        if (prev < 0) return
        val gap = gapBetween(
            prevType = adapter.getItemViewType(prev),
            prevTappable = adapter.itemAt(prev).openAppId != null,
            type = type,
            tappable = adapter.itemAt(position).openAppId != null,
        )
        outRect.top = gap?.let { res.getDimensionPixelSize(it) } ?: 0
    }

    companion object {
        /** The space above a row of [type] that follows a row of [prevType]; null for none. */
        @DimenRes
        fun gapBetween(prevType: Int, prevTappable: Boolean, type: Int, tappable: Boolean): Int? = when {
            // ponytail: a tappable receipt is 48 dp tall for touch; its extra height stands in for the 10 dp gap
            type == TYPE_RECEIPT && prevType == TYPE_RECEIPT -> if (tappable || prevTappable) null else R.dimen.gap_receipt
            type == TYPE_RECEIPT && prevType in HEADINGS -> R.dimen.gap_heading
            type == TYPE_NOTIFICATION_QUIET && prevType == TYPE_NOTIFICATION_QUIET -> R.dimen.gap_quiet_cards
            else -> R.dimen.gap_block
        }

        /** Rows that read as a heading for the receipts under them. */
        private val HEADINGS = setOf(TYPE_SENTENCE, TYPE_QUESTION, TYPE_RESULT_TEXT, TYPE_APPROVAL)
    }
}
