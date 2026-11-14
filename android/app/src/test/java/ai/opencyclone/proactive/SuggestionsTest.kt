package ai.opencyclone.proactive

import android.content.Context
import ai.opencyclone.R
import ai.opencyclone.db.AppDatabase
import ai.opencyclone.feed.ThreadKind
import ai.opencyclone.feed.ThreadRecorder
import ai.opencyclone.feed.ThreadState
import ai.opencyclone.tasks.WindowDao
import ai.opencyclone.tasks.WindowEntity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SuggestionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val windows = mockk<WindowDao>(relaxed = true)
    private val suggestions = Suggestions(context, windows)

    @Before fun reset() {
        context.getSharedPreferences(Suggestions.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        coEvery { windows.latest() } returns null
    }

    @Test fun offByDefaultAndOnlyAnActiveWindowAllowsIt() = runTest {
        assertFalse(suggestions.enabled)
        assertFalse(suggestions.allowed(100))
        coEvery { windows.latest() } returns WindowEntity(endsAt = 101, startedAt = 50)
        assertTrue(suggestions.allowed(100))
        assertFalse(suggestions.allowed(101))
        assertFalse(suggestions.allowed(49))
    }

    @Test fun onSurvivesANewInstanceWithoutAWindow() = runTest {
        suggestions.enabled = true
        assertTrue(Suggestions(context).allowed(100))
        coVerify(exactly = 0) { windows.latest() }
    }

    private suspend fun TestScope.receive(on: Boolean, window: Boolean, hidden: Boolean = false) {
        suggestions.enabled = on
        coEvery { windows.latest() } returns if (window) WindowEntity(endsAt = 100_000, startedAt = 0) else null
        val rules = mockk<Rules>()
        val hide = if (hidden) RuleEntity(kind = "package", packageName = "app", text = "Hide app", createdAt = 0) else null
        coEvery { rules.hide(any()) } returns hide
        coEvery { rules.textRules() } returns emptyList()
        val triage = mockk<Triage>(relaxed = true)
        val log = mockk<NotificationLogDao>(relaxed = true)
        var rows = 0
        val pipeline = Pipeline(backgroundScope, { emptySet() }, rules, triage, log, windows,
            { testScheduler.currentTime }, recordQueued = { ++rows; 1L },
            allowed = { suggestions.allowed(it) })
        pipeline.receive(Incoming("app", "key", "conversation", "Private title", "Private text", 0))
        advanceTimeBy(30_001); runCurrent()
        val shouldTriage = (on || window) && !hidden
        coVerify(exactly = if (shouldTriage) 1 else 0) { triage.process(any(), any()) }
        coVerify(exactly = if (hidden) 1 else 0) { log.insert(any()) }
        assertEquals(if (shouldTriage) 1 else 0, rows)
    }

    @Test fun offWithoutWindowRecordsNothingAndNeverTriages() = runTest { receive(false, false) }
    @Test fun offWithWindowRecordsAndTriages() = runTest { receive(false, true) }
    @Test fun onWithoutWindowRecordsAndTriages() = runTest { receive(true, false) }
    @Test fun hideRulesStillRunWhileOff() = runTest { receive(false, false, hidden = true) }

    @Test fun turningOffBeforeTheBatchPreventsTriage() = runTest {
        suggestions.enabled = true
        val rules = mockk<Rules>(relaxed = true)
        coEvery { rules.hide(any()) } returns null
        val triage = mockk<Triage>(relaxed = true)
        val discarded = mutableListOf<Long>()
        val pipeline = Pipeline(backgroundScope, { emptySet() }, rules, triage, mockk(relaxed = true), windows,
            { testScheduler.currentTime }, recordQueued = { 12L }, allowed = { suggestions.allowed(it) },
            discardQueued = { discarded.add(it.threadId) })
        pipeline.receive(Incoming("app", "key", "conversation", "Title", "Text", 0))
        suggestions.enabled = false
        advanceTimeBy(30_001); runCurrent()
        coVerify(exactly = 0) { triage.process(any(), any()) }
        assertEquals(listOf(12L), discarded)
    }

    @Test fun confirmationRequiresAnExplicitYesAndOffInvalidatesOldRows() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val recorder = ThreadRecorder(db.threadItemDao(), { System.currentTimeMillis() })
            suggestions.request(context, recorder, true)
            val row = recorder.today().first().single()
            assertEquals(ThreadKind.CONFIRM, row.kind)
            assertEquals(Suggestions.CONFIRM_REF, row.title)
            assertEquals(context.getString(R.string.suggestions_confirmation), row.text)
            assertEquals("Turn on", row.actionWord)
            assertFalse(suggestions.enabled)
            assertTrue(suggestions.confirm(recorder, row.id, false))
            assertFalse(suggestions.enabled)
            assertEquals(ThreadState.REJECTED, recorder.get(row.id)?.state)
            suggestions.confirm(recorder, row.id, true)
            assertFalse(suggestions.enabled)
            suggestions.request(context, recorder, true)
            val next = recorder.today().first().last()
            suggestions.confirm(recorder, next.id, true)
            assertTrue(suggestions.enabled)
            assertEquals(ThreadState.ANSWERED, recorder.get(next.id)?.state)
            suggestions.request(context, recorder, true)
            val pending = recorder.today().first().last()
            suggestions.request(context, recorder, false)
            assertFalse(suggestions.enabled)
            suggestions.confirm(recorder, pending.id, true)
            assertFalse(suggestions.enabled)
            assertEquals(context.getString(R.string.suggestions_stopped), recorder.today().first().last().text)
        } finally { db.close() }
    }
}
