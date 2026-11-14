package ai.opencyclone.debug

import ai.opencyclone.action.Action
import ai.opencyclone.config.ConfigRepository
import ai.opencyclone.gate.ApprovalReceiver
import ai.opencyclone.gate.ApprovalSession
import ai.opencyclone.gate.DeviceLock
import ai.opencyclone.gate.HeldAction
import ai.opencyclone.gate.HoldReason
import ai.opencyclone.gate.HoldResult
import ai.opencyclone.gate.ScreenFacts
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugCommandReceiverTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun immediate() { DebugCommandReceiver.dispatcher = Dispatchers.Unconfined }
    @After fun restore() { DebugCommandReceiver.dispatcher = Dispatchers.Default }

    private fun send(cmd: String?, vararg extras: Pair<String, String>) {
        val intent = Intent(DebugCommand.ACTION)
        cmd?.let { intent.putExtra(DebugCommand.EXTRA_CMD, it) }
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        DebugCommandReceiver().onReceive(context, intent)
    }

    @Test fun setModelWritesTheSettingsTheScreensRead() {
        send("set_model", "base_url" to "http://localhost:8765/v1", "model" to "walk-model")
        val config = ConfigRepository(context)
        assertEquals("openai", config.apiProvider)
        assertEquals("http://localhost:8765/v1", config.baseUrl)
        assertEquals("walk-model", config.model)
        assertEquals("walk", config.apiKey)
    }

    @Test fun badCommandsDoNotThrow() {
        send(null)
        send("launch_rockets")
        send("start")
        // No agent service in a unit test: start, answer and stop report it instead of crashing.
        send("start", "text" to "Open Gate Test")
        send("answer", "text" to "yes")
        send("stop")
    }

    private val session = ApprovalSession()

    // Stands in for ApprovalReceiver.onReceive, which builds the whole runtime (not possible in a unit test).
    private fun commands() = DebugCommands(context, { session }) { intent ->
        val id = intent.getLongExtra(ApprovalReceiver.EXTRA_TASK_ID, -1)
        when (intent.action) {
            ApprovalReceiver.ACTION_APPROVE -> DeviceLock.isUnlocked(context) && session.approve(id)
            ApprovalReceiver.ACTION_REJECT -> session.reject(id)
        }
    }

    private fun held(title: String?) = HeldAction(Action.Tap(500, 500), HoldReason.COMMIT_WORD, "Send",
        intArrayOf(0, 0, 10, 10), 5, 5, ScreenFacts("ai.opencyclone.testapp", title, null))

    private fun lock(locked: Boolean) =
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(locked)

    @Test fun approveNeedsTheButtonAndAnUnlockedPhone() = runBlocking {
        val noTitle = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(3, held(null)) }
        val notOffered = commands().run(DebugCommand.Approve, null)
        assertFalse(notOffered.ok)
        assertTrue(notOffered.line, notOffered.line.contains("not offered"))
        assertTrue(commands().run(DebugCommand.Reject, null).ok)
        assertEquals(HoldResult.Reject, noTitle.await())

        val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(4, held("Alex")) }
        lock(true)
        val locked = commands().run(DebugCommand.Approve, null)
        assertFalse(locked.ok)
        assertTrue(locked.line, locked.line.contains("locked"))
        assertEquals(4L, session.pending()?.taskId)
        lock(false)
        assertTrue(commands().run(DebugCommand.Approve, null).ok)
        assertEquals(HoldResult.Approve, waiting.await())
    }

    @Test fun editNeedsAnUnlockedPhoneAndPassesTheText() = runBlocking {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(5, held(null)) }
        lock(true)
        assertFalse(commands().run(DebugCommand.Edit("Send it to Sam"), null).ok)
        lock(false)
        assertTrue(commands().run(DebugCommand.Edit("Send it to Sam"), null).ok)
        assertEquals(HoldResult.Edit("Send it to Sam"), waiting.await())
    }

    @Test fun stateReportsTheWaitingApproval() = runBlocking {
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { session.awaitResult(6, held("Alex")) }
        val json = JSONObject(commands().run(DebugCommand.State, null).data!!)
        val approval = json.getJSONObject("approval")
        assertEquals(6, approval.getInt("task_id"))
        assertEquals("COMMIT_WORD", approval.getString("reason"))
        assertEquals("Send", approval.getString("label"))
        assertTrue(approval.getString("title").startsWith("Send in "))
        assertTrue(approval.getString("title").endsWith(" to Alex"))
        assertTrue(approval.getBoolean("approve_offered"))
        session.reject(6)
        waiting.await()
        Unit
    }

    @Test fun approvalCommandsWithNothingWaitingFail() = runBlocking {
        val commands = commands()
        for (command in listOf(DebugCommand.Approve, DebugCommand.Reject, DebugCommand.Edit("x"))) {
            val outcome = commands.run(command, null)
            assertFalse(command.toString(), outcome.ok)
            assertTrue(outcome.line, outcome.line.contains("no approval"))
        }
    }

    @Test fun startWithoutServiceFails() = runBlocking {
        val outcome = commands().run(DebugCommand.Start("Open Gate Test"), null)
        assertFalse(outcome.ok)
        assertTrue(outcome.line.contains("not running"))
    }

    @Test fun stateWritesTheJsonFileWithTheRequestId() = runBlocking {
        val outcome = commands().run(DebugCommand.State, "req-42")
        assertTrue(outcome.line, outcome.ok)
        val fromData = JSONObject(outcome.data!!)
        assertEquals("req-42", fromData.getString("request_id"))
        assertFalse(fromData.getBoolean("service_running"))
        val file = File(context.getExternalFilesDir(null), "walk/state.json")
        assertTrue(file.exists())
        assertEquals(outcome.data, file.readText())
        assertTrue(File(context.filesDir, "walk/state.json").exists())
    }
    @Test fun missingModelProviderDoesNotDeleteTheSavedKey() {
        val config = ConfigRepository(context)
        config.apiKey = "test-only-saved-key"
        send("set_model", "provider" to "walk-unconfigured", "base_url" to "http://localhost:8765/v1")
        assertEquals("walk-unconfigured", config.apiProvider)
        assertEquals("test-only-saved-key", config.apiKey)
    }

}
