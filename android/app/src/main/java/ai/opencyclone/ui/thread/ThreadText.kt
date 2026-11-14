package ai.opencyclone.ui.thread

import ai.opencyclone.R
import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.text.format.DateFormat
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Date

/** Small text helpers shared by the thread rows. */
internal object ThreadText {
    private val faces = HashMap<Int, Typeface>()

    /** Inter at [weight] (400, 500, 600). */
    fun inter(context: Context, weight: Int): Typeface = faces.getOrPut(weight) {
        val family = runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.DEFAULT
        Typeface.create(family, weight, false)
    }

    /** "now" within a minute of [now], otherwise the short time of day ("2:41 PM"). */
    fun shortTime(context: Context, at: Long, now: Long): String =
        if (now - at < 60_000L) context.getString(R.string.ui_now) else DateFormat.getTimeFormat(context).format(Date(at))

    /** Appends [text] with an optional weight and colour. */
    fun SpannableStringBuilder.add(
        context: Context,
        text: CharSequence,
        weight: Int? = null,
        @ColorRes color: Int? = null,
    ): SpannableStringBuilder {
        val start = length
        append(text)
        if (weight != null) setSpan(TypefaceSpan(inter(context, weight)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (color != null) {
            setSpan(ForegroundColorSpan(ContextCompat.getColor(context, color)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return this
    }

    /** The app's label from the package manager, or the package name when it is not installed. */
    fun appLabel(context: Context, packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}
