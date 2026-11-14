package ai.opencyclone.ui.thread

import ai.opencyclone.R
import ai.opencyclone.feed.ThreadItem
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.feed.ThreadState
import ai.opencyclone.ui.theme.Motion
import ai.opencyclone.ui.theme.fadeIn
import ai.opencyclone.ui.theme.fadeOut
import ai.opencyclone.ui.thread.ThreadText.add
import ai.opencyclone.ui.widget.AppIcons
import ai.opencyclone.ui.widget.ThumbnailGridView
import ai.opencyclone.ui.widget.WordRevealTextView
import ai.opencyclone.widgets.WidgetFlow
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TypefaceSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * Today's thread (UI.md sections 4, 5, 9, 10): one view for each [ThreadItem], driven only by the item.
 *
 * Conventions the engine follows when it writes items:
 * - SENTENCE, `detail == "heading"` ([DETAIL_HEADING]): the sentence is drawn in `Heading` (22 sp).
 * - SENTENCE, `detail == "phone-line"` ([DETAIL_PHONE_LINE]): drawn in `PhoneLine` (18 sp), the phone's
 *   sentence under the owner's words (5.8, 5.10).
 * - SENTENCE, any other `detail`: drawn in `Body`; the detail is not shown.
 * - RESULT with `detail` and `openAppId`: a result card; `text` is the card title, `detail` the caption,
 *   `title` (when set) the built app's name for its letter tile. Otherwise RESULT is a `Heading` sentence.
 * - PROGRESS: `text` + " · " + `detail`; `title` (when set) is the built app's name for its letter tile.
 * - OWNER: lines separated by `\n` make the list bubble; one line makes the dark single bubble.
 * - APPROVAL in state REJECTED or EXPIRED shows nothing; the engine adds a SENTENCE that says what happened.
 * - APPROVAL in state APPROVED with a `detail`: the detail (the result sentence) replaces the question, the evidence
 *   stays, no buttons; the task's own RESULT row is then not shown ([visible]).
 * - RESULT as a sentence: `Heading` up to [LONG_RESULT] characters, `Body` when longer.
 * - CONFIRM: the question in `Heading` (no line when it is empty); while OPEN a primary button with `actionWord`
 *   and a pale "Not now".
 * - RESULT with `actionWord == WidgetFlow.DRAFT_CARD`: a widget draft card; a tap calls [onConfirm] with yes.
 * - NOTIFICATION (card and quiet card): a tap calls [onOpen]; the home page opens the notification in its app.
 *
 * New rows (ids that arrive after the first list) fade in over [Motion.ENTER_MS], and their SENTENCE,
 * QUESTION, or RESULT text appears word by word. The first list (a restore) and every rebind show at once.
 *
 * The host must give its RecyclerView `clipChildren="false"` so that card shadows are not cut off.
 */
class ThreadAdapter : ListAdapter<ThreadItem, ThreadAdapter.Holder>(Diff) {

    /** Primary button of an open approval. */
    var onApprove: (taskId: Long) -> Unit = {}
    /** "Not now" of an open approval, or of a shared-app card that has a task id. */
    var onNotNow: (taskId: Long) -> Unit = {}
    /** A tap on something that opens: evidence, result card, progress pill, shared-app "Open", receipt with `openAppId`. */
    var onOpen: (item: ThreadItem) -> Unit = {}
    /** "Not now" of a shared-app card without a task id. */
    var onDismiss: (item: ThreadItem) -> Unit = {}
    /** CONFIRM: the primary button (yes) or "Not now" (no); a tap on a widget draft card (yes). */
    var onConfirm: (item: ThreadItem, yes: Boolean) -> Unit = { _, _ -> }
    /** Clock for " · now" (tests). */
    var now: () -> Long = System::currentTimeMillis

    private val seen = HashSet<Long>()
    private var primed = false

    override fun submitList(list: List<ThreadItem>?) {
        val shown = list?.let(::visible)
        prime(shown)
        super.submitList(shown)
    }

    override fun submitList(list: List<ThreadItem>?, commitCallback: Runnable?) {
        val shown = list?.let(::visible)
        prime(shown)
        super.submitList(shown, commitCallback)
    }

    /** The first list is what was there before (restore): it shows at once. */
    private fun prime(list: List<ThreadItem>?) {
        if (primed) return
        primed = true
        list?.forEach { seen += it.id }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        // Rows enter with the 250 ms fade, leave in 160 ms, and a changed row rebinds in place (no cross-fade),
        // so an approval's buttons can fade out on the same view.
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.apply {
            supportsChangeAnimations = false
            addDuration = Motion.ENTER_MS
            removeDuration = Motion.LEAVE_MS
        }
    }

    override fun getItemViewType(position: Int): Int = viewTypeOf(getItem(position))

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val inflater = LayoutInflater.from(parent.context)
        fun inflate(layout: Int) = inflater.inflate(layout, parent, false)
        return when (viewType) {
            TYPE_OWNER_SINGLE -> OwnerSingle(inflate(R.layout.item_thread_owner))
            TYPE_OWNER_LIST -> OwnerList(inflate(R.layout.item_thread_owner_list))
            TYPE_NOTIFICATION_CARD -> Notification(inflate(R.layout.item_thread_notification))
            TYPE_NOTIFICATION_QUIET -> Notification(inflate(R.layout.item_thread_notification_quiet))
            TYPE_SENTENCE, TYPE_QUESTION, TYPE_RESULT_TEXT -> Sentence(inflate(R.layout.item_thread_sentence))
            TYPE_APPROVAL -> Approval(inflate(R.layout.item_thread_approval))
            TYPE_RESULT_CARD -> ResultCard(inflate(R.layout.item_thread_result_card))
            TYPE_RECEIPT -> Receipt(inflate(R.layout.item_thread_receipt))
            TYPE_PROGRESS -> Progress(inflate(R.layout.item_thread_progress))
            TYPE_SHARED_APP -> SharedApp(inflate(R.layout.item_thread_shared_app))
            TYPE_CONFIRM -> Confirm(inflate(R.layout.item_thread_approval))
            else -> Hidden(View(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            })
        }
    }

    /**
     * The row itself is never animated here: its entry fade is the item animator's add animation (addDuration
     * [Motion.ENTER_MS]). Cancelling or starting the row's own ViewPropertyAnimator during a bind (which runs inside
     * a layout pass) ended the item animator's running animation at once, and RecyclerView then recycled a view that
     * was still temporarily detached ("Tmp detached view should be removed", seen on the phone). Only views inside the
     * row (buttons, thumbnails, words) animate, and they stop when the row is recycled or detached.
     */
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val fresh = seen.add(item.id)
        holder.bind(item, fresh)
        holder.boundId = item.id
        holder.boundState = item.state
    }

    override fun onViewRecycled(holder: Holder) {
        holder.stopAnimations()
        holder.boundId = null
        holder.boundState = null
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.stopAnimations()
    }

    override fun onFailedToRecycleView(holder: Holder): Boolean {
        holder.stopAnimations()
        return true
    }

    /** The item at [position] (decoration and tests). */
    internal fun itemAt(position: Int): ThreadItem = getItem(position)

    abstract class Holder(view: View) : RecyclerView.ViewHolder(view) {
        internal var boundId: Long? = null
        internal var boundState: ThreadState? = null
        protected val context: Context get() = itemView.context
        /** [fresh] is true the first time this item is shown: animate its entry. */
        abstract fun bind(item: ThreadItem, fresh: Boolean)

        /** Ends every animation of the views inside the row and leaves them in their final state. */
        open fun stopAnimations() {
            stopChildren(itemView)
        }

        private fun stopChildren(view: View) {
            if (view !== itemView) {
                view.animate().cancel()
                view.alpha = 1f
                view.setHasTransientState(false)
            }
            if (view is WordRevealTextView) view.showAll(view.text)
            if (view is ViewGroup) for (i in 0 until view.childCount) stopChildren(view.getChildAt(i))
        }
    }

    private class Hidden(view: View) : Holder(view) {
        override fun bind(item: ThreadItem, fresh: Boolean) = Unit
    }

    private class OwnerSingle(view: View) : Holder(view) {
        private val text: TextView = view.findViewById(R.id.owner_text)
        override fun bind(item: ThreadItem, fresh: Boolean) {
            text.maxWidth = bubbleMaxWidth(context)
            text.text = item.text
        }
    }

    private class OwnerList(view: View) : Holder(view) {
        private val lines: LinearLayout = view.findViewById(R.id.owner_lines)
        override fun bind(item: ThreadItem, fresh: Boolean) {
            lines.removeAllViews()
            val max = bubbleMaxWidth(context) - lines.paddingStart - lines.paddingEnd
            ownerLines(item.text).forEachIndexed { i, line ->
                lines.addView(TextView(context).apply {
                    setTextAppearance(R.style.TextAppearance_App_Body)
                    maxWidth = max
                    text = line
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = context.resources.getDimensionPixelSize(R.dimen.gap_receipt)
                })
            }
        }
    }

    private inner class Notification(view: View) : Holder(view) {
        private val icon: ImageView = view.findViewById(R.id.notification_icon)
        private val header: TextView = view.findViewById(R.id.notification_header)
        private val text: TextView = view.findViewById(R.id.notification_text)
        override fun bind(item: ThreadItem, fresh: Boolean) {
            AppIcons.into(icon, item.appPackage, item.title.orEmpty())
            icon.contentDescription = item.appPackage?.let {
                context.getString(R.string.ui_app_icon, ThreadText.appLabel(context, it))
            }
            val time = ThreadText.shortTime(context, item.at, now())
            header.text = SpannableStringBuilder().apply {
                item.title?.let { add(context, it, weight = 500, color = R.color.ink); add(context, context.getString(R.string.thread_dot_sep)) }
                add(context, time)
            }
            text.text = item.text
            // A tap opens the notification in its app, for the owner (ControlPanelActivity.open).
            val app = item.appPackage?.let { ThreadText.appLabel(context, it) }
            itemView.contentDescription = context.getString(R.string.x2_notification_desc,
                item.title ?: app.orEmpty(), app ?: item.title.orEmpty())
            itemView.setOnClickListener { onOpen(item) }
            itemView.minimumHeight = touchMin(context)
        }
    }

    private class Sentence(view: View) : Holder(view) {
        private val text: WordRevealTextView = view.findViewById(R.id.thread_text)
        override fun bind(item: ThreadItem, fresh: Boolean) {
            text.setTextAppearance(sentenceStyle(item))
            val wordMs = if (item.kind == ThreadKind.RESULT) Motion.WORD_LONG_SENTENCE_MS else Motion.WORD_MS
            if (fresh) text.reveal(item.text, wordMs) else text.showAll(item.text)
        }
    }

    private inner class Approval(view: View) : Holder(view) {
        private val question: WordRevealTextView = view.findViewById(R.id.approval_question)
        private val compose: TextView = view.findViewById(R.id.approval_compose)
        private val thumbs: ThumbnailGridView = view.findViewById(R.id.approval_thumbs)
        private val frame: ImageView = view.findViewById(R.id.approval_frame)
        private val buttons: View = view.findViewById(R.id.approval_buttons)
        private val primary: Button = view.findViewById(R.id.approval_primary)
        private val notNow: Button = view.findViewById(R.id.approval_not_now)

        init {
            frame.clipToOutline = true
        }

        override fun stopAnimations() {
            super.stopAnimations()
            // A fade-out that was cut short ends where it was going.
            if (boundState != null && boundState != ThreadState.OPEN) buttons.visibility = View.GONE
        }

        override fun bind(item: ThreadItem, fresh: Boolean) {
            // Approved and done: the result sentence replaces the question (UI.md section 10, 5.6).
            val result = approvedResult(item)
            question.setTextAppearance(if (result != null) resultStyle(result) else R.style.TextAppearance_App_Heading)
            val words = result ?: item.text
            if (fresh) question.reveal(words) else question.showAll(words)
            bindEvidence(item)

            val wasOpen = boundId == item.id && boundState == ThreadState.OPEN
            val open = item.state == ThreadState.OPEN
            val action = item.actionWord
            primary.visibility = if (item.canApprove && action != null && result == null) View.VISIBLE else View.GONE
            primary.text = action
            primary.contentDescription = action?.let { context.getString(R.string.thread_approve_desc, it, approvalTarget(item.text)) }
            primary.setOnClickListener { item.taskId?.let(onApprove) }
            notNow.setOnClickListener { item.taskId?.let(onNotNow) }

            when {
                open && fresh -> {
                    buttons.animate().cancel()
                    buttons.visibility = View.VISIBLE
                    buttons.alpha = 1f
                    val delay = if (thumbs.visibility == View.VISIBLE) thumbs.revealTiles() else 0L
                    enter(primary, delay)
                    enter(notNow, delay + ThumbnailGridView.STAGGER_MS)
                }
                open -> {
                    thumbs.showTiles()
                    buttons.animate().cancel()
                    buttons.visibility = View.VISIBLE
                    buttons.alpha = 1f
                    primary.alpha = 1f
                    notNow.alpha = 1f
                }
                wasOpen && result == null -> {
                    thumbs.showTiles()
                    buttons.fadeOut()
                }
                else -> {
                    thumbs.showTiles()
                    buttons.animate().cancel()
                    buttons.visibility = View.GONE
                }
            }
        }

        private fun enter(button: View, delay: Long) {
            button.animate().cancel()
            if (!ValueAnimator.areAnimatorsEnabled()) {
                button.alpha = 1f
                return
            }
            button.alpha = 0f
            button.animate().alpha(1f).setStartDelay(delay).setDuration(Motion.ENTER_MS).start()
        }

        private fun bindEvidence(item: ThreadItem) {
            val ev = item.evidence
            val open = View.OnClickListener { onOpen(item) }
            val text = ev?.composeText
            compose.visibility = if (text != null) View.VISIBLE else View.GONE
            compose.text = text
            compose.setOnClickListener(open)

            val count = ev?.count ?: 0
            val showThumbs = ev != null && (ev.thumbs.isNotEmpty() || (count > 0 && text == null && ev.framePath == null))
            thumbs.visibility = if (showThumbs) View.VISIBLE else View.GONE
            if (showThumbs) thumbs.setThumbs(ev!!.thumbs, ev.count) else thumbs.setThumbs(emptyList())
            thumbs.onTileClick = { onOpen(item) }

            val path = ev?.framePath?.takeIf { ev.thumbs.isEmpty() && text == null }
            val bitmap = path?.let { decodeFrame(it) }
            frame.visibility = if (bitmap != null) View.VISIBLE else View.GONE
            frame.setImageBitmap(bitmap)
            frame.setOnClickListener(open)
        }

        // ponytail: synchronous decode on the main thread, sampled down to the 220 dp height limit
        private fun decodeFrame(path: String) = runCatching {
            val maxH = (220 * context.resources.displayMetrics.density).toInt()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outHeight / (sample * 2) >= maxH) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private inner class ResultCard(view: View) : Holder(view) {
        private val card: View = view.findViewById(R.id.result_card)
        private val icon: ImageView = view.findViewById(R.id.result_icon)
        private val title: TextView = view.findViewById(R.id.result_title)
        private val detail: TextView = view.findViewById(R.id.result_detail)
        override fun bind(item: ThreadItem, fresh: Boolean) {
            icon.setImageDrawable(builtAppTile(context, item.title, R.dimen.icon_card))
            title.text = item.text
            detail.text = item.detail
            card.setOnClickListener { if (item.actionWord == WidgetFlow.DRAFT_CARD) onConfirm(item, true) else onOpen(item) }
        }
    }

    /** A yes-or-no question of the phone: the approval's look without evidence. */
    private inner class Confirm(view: View) : Holder(view) {
        private val question: WordRevealTextView = view.findViewById(R.id.approval_question)
        private val buttons: View = view.findViewById(R.id.approval_buttons)
        private val primary: Button = view.findViewById(R.id.approval_primary)
        private val notNow: Button = view.findViewById(R.id.approval_not_now)

        override fun stopAnimations() {
            super.stopAnimations()
            if (boundState != null && boundState != ThreadState.OPEN) buttons.visibility = View.GONE
        }

        override fun bind(item: ThreadItem, fresh: Boolean) {
            question.visibility = if (item.text.isBlank()) View.GONE else View.VISIBLE
            if (fresh) question.reveal(item.text) else question.showAll(item.text)
            val open = item.state == ThreadState.OPEN
            val wasOpen = boundId == item.id && boundState == ThreadState.OPEN
            primary.text = item.actionWord
            primary.visibility = if (item.actionWord != null) View.VISIBLE else View.GONE
            primary.setOnClickListener { onConfirm(item, true) }
            notNow.setOnClickListener { onConfirm(item, false) }
            buttons.animate().cancel()
            when {
                open -> { buttons.visibility = View.VISIBLE; buttons.alpha = 1f }
                wasOpen -> buttons.fadeOut()
                else -> buttons.visibility = View.GONE
            }
        }
    }

    private inner class Receipt(view: View) : Holder(view) {
        private val icon: ImageView = view.findViewById(R.id.receipt_icon)
        private val text: TextView = view.findViewById(R.id.receipt_text)
        override fun bind(item: ThreadItem, fresh: Boolean) {
            val pkg = item.appPackage
            val person = item.title
            when {
                pkg != null -> {
                    icon.visibility = View.VISIBLE
                    AppIcons.into(icon, pkg, person ?: item.actionWord.orEmpty())
                    icon.contentDescription = context.getString(R.string.ui_app_icon, ThreadText.appLabel(context, pkg))
                }
                person != null -> {
                    icon.visibility = View.VISIBLE
                    icon.setImageDrawable(avatar(context, person, context.resources.getDimensionPixelSize(R.dimen.icon_receipt)))
                    icon.contentDescription = person
                }
                else -> icon.visibility = View.INVISIBLE
            }
            text.text = SpannableStringBuilder().apply {
                item.actionWord?.let { add(context, it, weight = 600, color = R.color.ink) }
                if (item.actionWord != null && item.text.isNotEmpty()) add(context, context.getString(R.string.thread_dot_sep), color = R.color.ink_2)
                if (item.text.isNotEmpty()) add(context, item.text, color = R.color.ink_2)
            }
            val tappable = item.openAppId != null
            itemView.minimumHeight = if (tappable) touchMin(context) else 0
            if (tappable) itemView.setOnClickListener { onOpen(item) } else {
                itemView.setOnClickListener(null)
                itemView.isClickable = false
            }
        }
    }

    private inner class Progress(view: View) : Holder(view) {
        private val pill: View = view.findViewById(R.id.progress_pill)
        private val icon: ImageView = view.findViewById(R.id.progress_icon)
        private val text: TextView = view.findViewById(R.id.progress_text)
        override fun bind(item: ThreadItem, fresh: Boolean) {
            val tappable = item.openAppId != null
            icon.visibility = if (tappable) View.VISIBLE else View.GONE
            if (tappable) icon.setImageDrawable(builtAppTile(context, item.title, R.dimen.icon_pill))
            text.text = SpannableStringBuilder().apply {
                add(context, item.text, weight = 500, color = R.color.ink)
                item.detail?.let { add(context, context.getString(R.string.thread_dot_sep) + it, color = R.color.ink_2) }
                if (tappable) add(context, " ›", color = R.color.ink_2)
            }
            if (tappable) pill.setOnClickListener { onOpen(item) } else {
                pill.setOnClickListener(null)
                pill.isClickable = false
            }
        }
    }

    private inner class SharedApp(view: View) : Holder(view) {
        private val card: View = view.findViewById(R.id.shared_card)
        private val glyph: ImageView = view.findViewById(R.id.shared_glyph)
        private val time: TextView = view.findViewById(R.id.shared_time)
        private val text: TextView = view.findViewById(R.id.shared_text)
        private val appIcon: ImageView = view.findViewById(R.id.shared_app_icon)
        private val appName: TextView = view.findViewById(R.id.shared_app_name)
        private val checked: TextView = view.findViewById(R.id.shared_checked)
        private val buttons: View = view.findViewById(R.id.shared_buttons)
        private val open: Button = view.findViewById(R.id.shared_open)
        private val notNow: Button = view.findViewById(R.id.shared_not_now)

        init {
            card.background = sharedCardBackground(context)
            val size = context.resources.getDimensionPixelSize(R.dimen.icon_notification)
            glyph.setImageDrawable(
                AppIcons.load(context, context.packageName, size)
                    ?: AppIcons.letterTile(context, context.getString(R.string.app_name), ContextCompat.getColor(context, R.color.ink), size),
            )
        }

        override fun bind(item: ThreadItem, fresh: Boolean) {
            time.text = ThreadText.shortTime(context, item.at, now())
            text.text = sharedText(context, item.title, item.text)
            appIcon.setImageDrawable(builtAppTile(context, item.detail, R.dimen.icon_tile_large))
            appName.text = item.detail
            appName.visibility = if (item.detail != null) View.VISIBLE else View.GONE
            checked.text = item.title?.let { context.getString(R.string.thread_checked_sent_by, it) }
                ?: context.getString(R.string.thread_checked)
            buttons.visibility = if (item.state == ThreadState.OPEN) View.VISIBLE else View.GONE
            open.setOnClickListener { onOpen(item) }
            notNow.setOnClickListener { item.taskId?.let(onNotNow) ?: onDismiss(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ThreadItem>() {
        override fun areItemsTheSame(oldItem: ThreadItem, newItem: ThreadItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ThreadItem, newItem: ThreadItem) = oldItem == newItem
    }

    companion object {
        const val DETAIL_HEADING = "heading"
        const val DETAIL_PHONE_LINE = "phone-line"

        const val TYPE_HIDDEN = 0
        const val TYPE_OWNER_SINGLE = 1
        const val TYPE_OWNER_LIST = 2
        const val TYPE_NOTIFICATION_CARD = 3
        const val TYPE_NOTIFICATION_QUIET = 4
        const val TYPE_SENTENCE = 5
        const val TYPE_QUESTION = 6
        const val TYPE_APPROVAL = 7
        const val TYPE_RESULT_TEXT = 8
        const val TYPE_RESULT_CARD = 9
        const val TYPE_RECEIPT = 10
        const val TYPE_PROGRESS = 11
        const val TYPE_SHARED_APP = 12
        const val TYPE_CONFIRM = 13

        fun viewTypeOf(item: ThreadItem): Int = when (item.kind) {
            ThreadKind.OWNER -> if (ownerLines(item.text).size > 1) TYPE_OWNER_LIST else TYPE_OWNER_SINGLE
            ThreadKind.NOTIFICATION -> if (item.state == ThreadState.OPEN) TYPE_NOTIFICATION_CARD else TYPE_NOTIFICATION_QUIET
            ThreadKind.SENTENCE -> TYPE_SENTENCE
            ThreadKind.QUESTION -> TYPE_QUESTION
            ThreadKind.APPROVAL -> when (item.state) {
                ThreadState.REJECTED, ThreadState.EXPIRED -> TYPE_HIDDEN
                else -> TYPE_APPROVAL
            }
            ThreadKind.RESULT -> if (item.detail != null && (item.openAppId != null || item.actionWord == WidgetFlow.DRAFT_CARD))
                TYPE_RESULT_CARD else TYPE_RESULT_TEXT
            ThreadKind.RECEIPT -> TYPE_RECEIPT
            ThreadKind.PROGRESS -> TYPE_PROGRESS
            ThreadKind.SHARED_APP -> TYPE_SHARED_APP
            ThreadKind.CONFIRM -> TYPE_CONFIRM
        }

        internal fun ownerLines(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        /** A result sentence longer than this is drawn in `Body`; a short one in `Heading`. */
        const val LONG_RESULT = 60

        @StyleRes
        internal fun resultStyle(text: String): Int =
            if (text.length > LONG_RESULT) R.style.TextAppearance_App_Body else R.style.TextAppearance_App_Heading

        /** The result sentence an approved approval shows in place of its question, or null. */
        internal fun approvedResult(item: ThreadItem): String? =
            item.detail?.takeIf { item.kind == ThreadKind.APPROVAL && item.state == ThreadState.APPROVED && it.isNotBlank() }

        /** The rows that are drawn: a RESULT row is left out when its task's approved approval shows the result. */
        internal fun visible(list: List<ThreadItem>): List<ThreadItem> {
            val shownInApproval = list.mapNotNullTo(HashSet()) { if (approvedResult(it) != null) it.taskId else null }
            if (shownInApproval.isEmpty()) return list
            return list.filterNot {
                it.kind == ThreadKind.RESULT && it.taskId != null && it.taskId in shownInApproval && viewTypeOf(it) == TYPE_RESULT_TEXT
            }
        }

        @StyleRes
        internal fun sentenceStyle(item: ThreadItem): Int = when {
            item.kind == ThreadKind.RESULT -> resultStyle(item.text)
            item.kind == ThreadKind.QUESTION -> R.style.TextAppearance_App_Heading
            item.detail == DETAIL_HEADING -> R.style.TextAppearance_App_Heading
            item.detail == DETAIL_PHONE_LINE -> R.style.TextAppearance_App_PhoneLine
            else -> R.style.TextAppearance_App_Body
        }

        /** "9 photos to Sam. Send them?" gives "9 photos to Sam": the part that names the target. */
        internal fun approvalTarget(heading: String): String =
            heading.substringBefore(". ").trimEnd('.', '?').ifEmpty { heading }

        private fun bubbleMaxWidth(context: Context): Int {
            val res = context.resources
            val page = res.displayMetrics.widthPixels - 2 * res.getDimensionPixelSize(R.dimen.margin_page)
            return (page * 0.8f).toInt()
        }

        internal fun touchMin(context: Context): Int = (48 * context.resources.displayMetrics.density).toInt()

        /** A built app's letter tile in the default accent; no letter when [name] is null. */
        private fun builtAppTile(context: Context, name: String?, sizeRes: Int): Drawable =
            AppIcons.letterTile(context, name.orEmpty(), ContextCompat.getColor(context, R.color.accent_default),
                context.resources.getDimensionPixelSize(sizeRes))

        /** A round avatar with the white initial of [name] (a receipt about a person). */
        private fun avatar(context: Context, name: String, sizePx: Int): Drawable {
            val size = sizePx.coerceAtLeast(1)
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ink_2) })
            val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = size * 0.55f
                typeface = ThreadText.inter(context, 600)
            }
            canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
            return BitmapDrawable(context.resources, bitmap)
        }

        /** The sender's name in weight 600 when the text starts with it; the text itself is not changed. */
        private fun sharedText(context: Context, sender: String?, text: String): CharSequence {
            if (sender.isNullOrEmpty() || !text.startsWith(sender)) return text
            return SpannableString(text).apply {
                setSpan(TypefaceSpan(ThreadText.inter(context, 600)), 0, sender.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        /**
         * `surface`, radius 24 dp, with a faint pink-to-violet wash along the top edge.
         * ponytail: the wash is a 3 dp band of a horizontal gradient under the surface, not a blurred glow.
         */
        private fun sharedCardBackground(context: Context): Drawable {
            val radius = context.resources.getDimension(R.dimen.radius_shared)
            val pink = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.accent_default), 0x40)
            val violet = ContextCompat.getColor(context, R.color.wash_cool_violet)
            val edge = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pink, violet)).apply { cornerRadius = radius }
            val surface = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface))
                cornerRadius = radius
            }
            val band = (3 * context.resources.displayMetrics.density).toInt()
            return LayerDrawable(arrayOf(edge, surface)).apply { setLayerInset(1, 0, band, 0, 0) }
        }
    }
}
