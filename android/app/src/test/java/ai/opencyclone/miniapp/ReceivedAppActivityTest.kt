package ai.opencyclone.miniapp

import ai.opencyclone.R
import ai.opencyclone.db.AppDatabase
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceivedAppActivityTest {
    private lateinit var f: StoreFixture
    private val tested = mutableListOf<String>()
    private var testProblems = emptyList<String>()
    private val uri = Uri.parse("content://com.example.messenger/files/Spanish.html")

    @Before fun setUp() {
        f = StoreFixture()
        ReceivedAppActivity.databaseFor = { f.db }
        ReceivedAppActivity.testerFor = { { html -> tested += html; testProblems } }
    }

    @After fun tearDown() {
        ReceivedAppActivity.databaseFor = { AppDatabase.getInstance(it) }
        ReceivedAppActivity.testerFor = { context -> { html -> Tester(context).test(html, allowFetch = false) } }
        f.close()
    }

    private fun waitFor(done: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("condition not met")
    }

    private fun start(intent: Intent, stream: InputStream? = null): ActivityController<ReceivedAppActivity> {
        val controller = Robolectric.buildActivity(ReceivedAppActivity::class.java, intent)
        stream?.let { shadowOf(controller.get().contentResolver).registerInputStream(intent.data ?: uri, it) }
        controller.setup()
        val activity = controller.get()
        waitFor { activity.findViewById<View>(R.id.received_card).visibility == View.VISIBLE }
        return controller
    }

    private fun view(html: String) = start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html"),
        ByteArrayInputStream(html.toByteArray()))

    private fun ReceivedAppActivity.text(id: Int) = findViewById<TextView>(id).text.toString()
    private fun ReceivedAppActivity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    @Test fun aCleanFileShowsTheCardWithOpen() {
        val activity = view(receivedFixture()).get()
        assertEquals("Spanish", activity.text(R.id.received_app_name))
        assertEquals(activity.getString(R.string.received_checked), activity.text(R.id.received_status))
        assertTrue(activity.visible(R.id.received_shield))
        assertTrue(activity.visible(R.id.received_open))
        assertEquals(activity.getString(R.string.ui_not_now), activity.text(R.id.received_dismiss))
        assertFalse(activity.visible(R.id.received_findings))
        // Nothing is written before Open.
        assertEquals(0, runBlocking { f.store.list() }.size)
    }

    @Test fun openStoresExactlyOneNewAppAndStartsIt() {
        val activity = view(receivedFixture()).get()
        activity.findViewById<Button>(R.id.received_open).performClick()
        waitFor { activity.isFinishing }
        val apps = runBlocking { f.store.list() }
        assertEquals(listOf("Spanish"), apps.map { it.name })
        assertTrue("a received app is marked and gets no internet", apps[0].received)
        val stored = runBlocking { f.store.currentHtml(apps[0].id) }!!
        assertTrue("our style block is in place", stored.contains("id=\"${BuiltAppStyle.STYLE_ID}\""))
        assertEquals(listOf(stored), tested)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(MiniAppActivity::class.java.name, started.component?.className)
        assertEquals(apps[0].id, started.getLongExtra(MiniAppActivity.EXTRA_APP_ID, -1))
    }

    @Test fun aNameClashStoresTheNextNumber() {
        val (existing, _) = runBlocking { f.store.saveNewVersion(null, "Spanish", page("Spanish", "mine")) }
        val activity = view(receivedFixture()).get()
        activity.findViewById<Button>(R.id.received_open).performClick()
        waitFor { activity.isFinishing }
        assertEquals(listOf("Spanish", "Spanish 2"), runBlocking { f.store.list() }.map { it.name })
        assertTrue("the owner's app is unchanged", runBlocking { f.store.currentHtml(existing) }!!.contains("mine"))
    }

    @Test fun notNowStoresNothing() {
        val activity = view(receivedFixture()).get()
        activity.findViewById<Button>(R.id.received_dismiss).performClick()
        assertTrue(activity.isFinishing)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, runBlocking { f.store.list() }.size)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aFailingFileShowsFindingsAndNoPrimaryButton() {
        val activity = view(receivedFixture(body = """<img src="https://example.com/p.png"><script>eval(x)</script>""")).get()
        assertFalse(activity.visible(R.id.received_open))
        assertFalse(activity.visible(R.id.received_shield))
        assertEquals(activity.getString(R.string.received_not_opened), activity.text(R.id.received_status))
        assertEquals(activity.getString(R.string.received_close), activity.text(R.id.received_dismiss))
        val lines = activity.text(R.id.received_findings).lines()
        assertEquals(listOf(activity.getString(R.string.received_finding_outside_address),
            activity.getString(R.string.received_finding_runs_text)), lines)
    }

    @Test fun aFailedLoadTestIsShownLikeAFailedCheck() {
        testProblems = listOf("Uncaught ReferenceError: x (line 3)")
        val activity = view(receivedFixture()).get()
        activity.findViewById<Button>(R.id.received_open).performClick()
        waitFor { !activity.visible(R.id.received_open) }
        assertEquals(activity.getString(R.string.received_finding_load_failed), activity.text(R.id.received_findings))
        assertEquals(0, runBlocking { f.store.list() }.size)
        assertFalse(activity.isFinishing)
    }

    @Test fun anOversizedStreamIsRefusedWithoutReadingAllOfIt() {
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); served += len; return len }
        }
        val activity = start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html"), endless).get()
        assertTrue("read $served bytes", served <= ReceivedAppChecks.MAX_BYTES + 1)
        assertEquals(activity.getString(R.string.received_finding_file_too_large), activity.text(R.id.received_findings))
        assertFalse(activity.visible(R.id.received_open))
    }

    @Test fun sendWithAStreamIsRead() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/html").putExtra(Intent.EXTRA_STREAM, uri)
        val controller = Robolectric.buildActivity(ReceivedAppActivity::class.java, intent)
        shadowOf(controller.get().contentResolver).registerInputStream(uri, ByteArrayInputStream(receivedFixture().toByteArray()))
        val activity = controller.setup().get()
        waitFor { activity.visible(R.id.received_card) }
        assertTrue(activity.visible(R.id.received_open))
    }

    @Test fun anOldFileUriIsRead() {
        val file = File.createTempFile("Spanish", ".html").apply { writeText(receivedFixture()); deleteOnExit() }
        val activity = start(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "text/html")).get()
        assertTrue(activity.visible(R.id.received_open))
    }

    @Test fun ourOwnFilesAreRefused() {
        val file = File(f.context.filesDir, "secret.html").apply { writeText(receivedFixture()) }
        try {
            val activity = start(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "text/html")).get()
            assertFalse(activity.visible(R.id.received_open))
            assertEquals(activity.getString(R.string.received_finding_unreadable), activity.text(R.id.received_findings))
        } finally {
            file.delete()
        }
    }

    @Test fun invalidUtf8IsRefused() {
        val activity = start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html"),
            ByteArrayInputStream(byteArrayOf(0x3c, 0xff.toByte(), 0xfe.toByte(), 0x3e))).get()
        assertEquals(activity.getString(R.string.received_finding_not_text), activity.text(R.id.received_findings))
    }

    @Test fun readLimitedStopsOneByteAfterTheLimit() {
        assertEquals(11, ReceivedAppActivity.readLimited(ByteArrayInputStream(ByteArray(100)), 10).size)
        assertEquals(5, ReceivedAppActivity.readLimited(ByteArrayInputStream(ByteArray(5)), 10).size)
    }
}
