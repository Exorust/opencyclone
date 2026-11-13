package ai.opencyclon.ui.widget

import ai.opencyclon.R
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChipRowViewTest {
    private val context: Context
        get() = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_OpenCyclon)

    @Test fun chipsShowInOrderAndDoneHasACheck() {
        val row = ChipRowView(context)
        row.setChips(listOf(ChipRowView.Chip(1, "Lingo"), ChipRowView.Chip(2, "Budget", done = true)))
        val views = row.chipViews()
        assertEquals(listOf("Lingo", "Budget"), views.map { it.text.toString() })
        assertNull(views[0].compoundDrawablesRelative[2])
        assertNotNull(views[1].compoundDrawablesRelative[2])
        assertEquals("Budget, done", (views[1].parent as View).contentDescription)
    }

    @Test fun eachChipHasA48dpTouchAreaAnd8dpGaps() {
        val row = ChipRowView(context)
        row.onChipClick = {}
        row.setChips(listOf(ChipRowView.Chip(1, "A"), ChipRowView.Chip(2, "B")))
        val second = row.chipViews()[1].parent as View
        val lp = second.layoutParams as LinearLayout.LayoutParams
        assertEquals((48 * context.resources.displayMetrics.density).toInt(), lp.height)
        assertEquals(context.resources.getDimensionPixelSize(R.dimen.gap_chip), lp.marginStart)
        assertEquals(context.resources.getDimensionPixelSize(R.dimen.chip_height), row.chipViews()[0].minHeight)
    }

    @Test fun tapReportsTheChip() {
        val row = ChipRowView(context)
        var tapped: ChipRowView.Chip? = null
        row.setChips(listOf(ChipRowView.Chip(7, "Lingo")))
        row.onChipClick = { tapped = it }
        (row.chipViews()[0].parent as View).performClick()
        assertEquals(7L, tapped?.id)
    }
}
