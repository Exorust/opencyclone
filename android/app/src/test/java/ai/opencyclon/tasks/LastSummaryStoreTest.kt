package ai.opencyclon.tasks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LastSummaryStoreTest {
    @Test fun preferencesStoreKeepsTheTimeForANewInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("last_summary_test", Context.MODE_PRIVATE)
        assertEquals(0L, LastSummaryStore.Prefs(prefs).get())
        LastSummaryStore.Prefs(prefs).set(1_234L)
        val reopened = context.getSharedPreferences("last_summary_test", Context.MODE_PRIVATE)
        assertEquals(1_234L, LastSummaryStore.Prefs(reopened).get())
    }
}
