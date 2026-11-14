package ai.opencyclone.miniapp

import ai.opencyclone.db.AppDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A received app gets no fetch, keeps the mark across versions, and the 8 to 9 migration adds the mark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReceivedAppNoInternetTest {
    private lateinit var f: StoreFixture
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        f = StoreFixture()
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        server.shutdown()
        f.close()
    }

    /** Calls fetch and returns the (text, error) the page would receive. */
    private fun fetch(bridge: (deliver: (String) -> Unit) -> Bridge, url: String): String {
        val latch = CountDownLatch(1)
        var delivered = ""
        bridge { js -> delivered = js; latch.countDown() }.fetch(url, JSONObject().put("id", "a1").toString())
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return delivered
    }

    private val everythingPublic: (String) -> List<InetAddress> = { listOf(InetAddress.getByAddress(it, byteArrayOf(93, 184.toByte(), 215.toByte(), 14))) }

    @Test fun withoutFetchNoRequestLeavesAndThePageGetsTheError() {
        val js = fetch({ d -> Bridge(f.context, 7, d, allowNotify = false, allowFetch = false, resolve = everythingPublic) },
            server.url("/data").toString())
        assertTrue(js.contains("\"a1\", null, " + JSONObject.quote(Bridge.NO_INTERNET)))
        val https = fetch({ d -> Bridge(f.context, 7, d, allowNotify = false, allowFetch = false, resolve = everythingPublic) },
            "https://example.com/data")
        assertTrue(https.contains(JSONObject.quote(Bridge.NO_INTERNET)))
        assertEquals(0, server.requestCount)
        assertEquals("This app cannot use the internet.", Bridge.NO_INTERNET)
    }

    @Test fun withFetchTheUsualChecksRunInstead() {
        // The same local address is refused by the address check, not by the received flag.
        val js = fetch({ d -> Bridge(f.context, 7, d, allowNotify = false) }, server.url("/data").toString())
        assertTrue(js.contains(JSONObject.quote(Bridge.REFUSED_URL)))
        assertFalse(js.contains(Bridge.NO_INTERNET))
        assertEquals(0, server.requestCount)
    }

    @Test fun receivedMarkIsSetAndKeptAcrossANewVersion() = runBlocking {
        val (built, _) = f.store.saveNewVersion(null, "Mine", page("Mine"))
        val (received, _) = f.store.saveNewVersion(null, "Sent", page("Sent"), received = true)
        assertFalse(f.store.isReceived(built))
        assertTrue(f.store.isReceived(received))
        // Changed by asking (F8): the builder saves a new version without the flag; the mark stays.
        f.store.saveNewVersion(received, "Sent", page("Sent", "changed"))
        assertTrue(f.store.isReceived(received))
        assertEquals(2, f.db.miniAppDao().get(received)!!.currentVersion)
    }

    @Test fun changingAReceivedAppTestsItWithoutInternet() = runBlocking {
        val (id, _) = f.store.saveNewVersion(null, "Sent", page("Sent"), received = true)
        val fetchFlags = mutableListOf<Boolean>()
        var calls = 0
        val builder = Builder(
            model = { calls++; if (calls == 1) """{"questions":[],"refuse":null}""" else
                "<html><head><meta name=\"accent\" content=\"#4733D6\"><title>Sent</title></head><body>Changed</body></html>" },
            ask = { "" },
            test = { _, allowFetch -> fetchFlags += allowFetch; emptyList() },
            store = f.store,
            progress = {}
        )
        assertTrue(builder.change(id, "make it blue") is BuildResult.Ready)
        assertEquals(listOf(false), fetchFlags)
        assertTrue(f.store.isReceived(id))
    }

    @Test fun migration8To9AddsTheReceivedMark() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()).name(null).callback(object : SupportSQLiteOpenHelper.Callback(8) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_5_6.migrate(db)
            AppDatabase.MIGRATION_6_7.migrate(db)
            AppDatabase.MIGRATION_7_8.migrate(db)
            db.execSQL("INSERT INTO miniapps (name, current_version, created_at, accent) VALUES ('Lingo', 2, 1, '#112233')")
            AppDatabase.MIGRATION_8_9.migrate(db)
            db.query("SELECT name, accent, received FROM miniapps").use {
                assertTrue(it.moveToFirst())
                assertEquals("Lingo", it.getString(0))
                assertEquals("#112233", it.getString(1))
                assertEquals(0, it.getInt(2))
            }
        } finally { helper.close() }
    }
}
