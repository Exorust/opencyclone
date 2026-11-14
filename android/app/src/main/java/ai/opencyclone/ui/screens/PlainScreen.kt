package ai.opencyclone.ui.screens

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.opencyclone.R
import ai.opencyclone.ui.theme.Wash
import ai.opencyclone.ui.theme.applySystemBarPadding
import ai.opencyclone.ui.theme.drawBehindSystemBars
import ai.opencyclone.ui.theme.setWash

/**
 * The plain look of the secondary screens (UI.md 5.12): Warm wash behind the system bars, a title in
 * `Greeting` size, an optional lead sentence, a column of rows, and a bottom bar with at most one
 * primary and one pale button that stays above the keyboard.
 */
class PlainScreen private constructor(private val activity: Activity, val root: View) {

    val title: TextView = root.findViewById(R.id.screen_title)
    val lead: TextView = root.findViewById(R.id.screen_lead)
    val rows: LinearLayout = root.findViewById(R.id.screen_rows)
    val primary: Button = root.findViewById(R.id.screen_primary)
    val pale: Button = root.findViewById(R.id.screen_pale)
    private val bottom: View = root.findViewById(R.id.screen_bottom)
    private val inflater = LayoutInflater.from(activity)

    companion object {
        /** Sets the activity's content to a plain screen with [title] and returns it. */
        fun install(activity: Activity, title: CharSequence, lead: CharSequence? = null): PlainScreen {
            activity.drawBehindSystemBars()
            // The bottom bar is padded by the keyboard inset; the window must resize for it.
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val root = LayoutInflater.from(activity).inflate(R.layout.screen_plain, null)
            root.setWash(Wash.Warm)
            activity.setContentView(root)
            applySystemBarPadding(root)
            activity.title = title
            return PlainScreen(activity, root).apply {
                this.title.text = title
                if (lead != null) {
                    this.lead.text = lead
                    this.lead.visibility = View.VISIBLE
                }
            }
        }
    }

    /** A section title in `Heading`, a block's space above it. */
    fun section(text: CharSequence, parent: ViewGroup = rows): TextView = TextView(activity).apply {
        setTextAppearance(R.style.TextAppearance_App_Heading)
        this.text = text
        isAccessibilityHeading = true
        parent.addView(this, marginTop(R.dimen.gap_block))
    }

    /** One sentence in `Body`. */
    fun sentence(text: CharSequence, parent: ViewGroup = rows): TextView = TextView(activity).apply {
        setTextAppearance(R.style.TextAppearance_App_Body)
        this.text = text
        parent.addView(this, marginTop(R.dimen.gap_chip))
    }

    /** A short caption in `Small`. */
    fun caption(text: CharSequence, parent: ViewGroup = rows): TextView = TextView(activity).apply {
        setTextAppearance(R.style.TextAppearance_App_Small)
        this.text = text
        parent.addView(this, marginTop(R.dimen.gap_chip))
    }

    /**
     * A text row on `surface_2`: [title], optional [detail] in `Small`, and at the end one of
     * [trailing] text, a chevron, or a pale pill [action]. [onClick] makes the whole row tappable.
     */
    fun textRow(
        title: CharSequence,
        detail: CharSequence? = null,
        trailing: CharSequence? = null,
        chevron: Boolean = false,
        action: CharSequence? = null,
        onAction: ((Button) -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        parent: ViewGroup = rows,
    ): View {
        val row = inflater.inflate(R.layout.row_text, parent, false)
        row.findViewById<TextView>(R.id.row_title).text = title
        row.findViewById<TextView>(R.id.row_detail).showText(detail)
        row.findViewById<TextView>(R.id.row_trailing).showText(trailing)
        row.findViewById<ImageView>(R.id.row_chevron).visibility = if (chevron) View.VISIBLE else View.GONE
        if (action != null) {
            row.findViewById<Button>(R.id.row_action).apply {
                text = action
                visibility = View.VISIBLE
                setOnClickListener { onAction?.invoke(this) }
            }
        }
        if (onClick != null) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onClick() }
        }
        parent.addView(row)
        return row
    }

    /** A receipt-style status row: check glyph when [granted]; otherwise [detail] and a pale "Grant" pill. */
    fun statusRow(
        text: CharSequence,
        granted: Boolean,
        detail: CharSequence? = null,
        onGrant: (() -> Unit)? = null,
        parent: ViewGroup = rows,
    ): View {
        val row = inflater.inflate(R.layout.row_status, parent, false)
        row.findViewById<TextView>(R.id.status_text).text = text
        row.findViewById<ImageView>(R.id.status_check).visibility = if (granted) View.VISIBLE else View.GONE
        row.findViewById<TextView>(R.id.status_detail).showText(if (granted) null else detail)
        row.findViewById<Button>(R.id.status_grant).apply {
            visibility = if (granted) View.GONE else View.VISIBLE
            contentDescription = activity.getString(R.string.screens_grant) + " " + text
            setOnClickListener { onGrant?.invoke() }
        }
        parent.addView(row)
        return row
    }

    /** A text-field row: [label] above, the field on `surface_2`. Returns the field. */
    fun fieldRow(
        label: CharSequence,
        inputType: Int,
        text: CharSequence? = null,
        hint: CharSequence? = null,
        parent: ViewGroup = rows,
    ): EditText {
        val row = inflater.inflate(R.layout.row_field, parent, false)
        row.findViewById<TextView>(R.id.field_label).text = label
        val field = row.findViewById<EditText>(R.id.field_input)
        field.inputType = inputType
        field.hint = hint
        if (text != null) field.setText(text)
        parent.addView(row)
        return field
    }

    /** A toggle row on `surface_2`. Returns the switch. */
    fun toggleRow(
        label: CharSequence,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
        parent: ViewGroup = rows,
    ): CompoundButton {
        val row = inflater.inflate(R.layout.row_toggle, parent, false)
        row.findViewById<TextView>(R.id.toggle_label).text = label
        val toggle = row.findViewById<CompoundButton>(R.id.toggle_switch)
        toggle.contentDescription = label
        toggle.isChecked = checked
        toggle.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        parent.addView(row)
        return toggle
    }

    /** Shows the one primary button of the screen. */
    fun primary(text: CharSequence, onClick: () -> Unit): Button = primary.also { show(it, text, onClick) }

    /** Shows the pale button beside the primary one. */
    fun pale(text: CharSequence, onClick: () -> Unit): Button = pale.also { show(it, text, onClick) }

    private fun show(button: Button, text: CharSequence, onClick: () -> Unit) {
        button.text = text
        button.visibility = View.VISIBLE
        button.setOnClickListener { onClick() }
        bottom.visibility = View.VISIBLE
    }

    private fun TextView.showText(value: CharSequence?) {
        text = value
        visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun marginTop(dimen: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = activity.resources.getDimensionPixelSize(dimen) }
}
