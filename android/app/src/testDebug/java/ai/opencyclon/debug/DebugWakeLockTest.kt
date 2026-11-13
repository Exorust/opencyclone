package ai.opencyclon.debug

import ai.opencyclon.AgentService
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebugWakeLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun parseNeedsAKnownMode() {
        assertEquals(DebugCommand.WakeLock("partial"), DebugCommand.parse("wake_lock") { if (it == "mode") "partial" else null })
        assertTrue(DebugCommand.parse("wake_lock") { null } is DebugCommand.Invalid)
        assertTrue(DebugCommand.parse("wake_lock") { "dim" } is DebugCommand.Invalid)
    }

    @Test fun commandSwitchesTheModeAndStateReportsIt() = runBlocking {
        assertEquals(AgentService.WAKE_LOCK_SCREEN, AgentService.wakeLockMode(context))
        val outcome = DebugCommands(context).run(DebugCommand.WakeLock("partial"), null)
        assertTrue(outcome.ok)
        assertEquals(AgentService.WAKE_LOCK_PARTIAL, AgentService.wakeLockMode(context))
        AgentService.setWakeLockMode(context, AgentService.WAKE_LOCK_SCREEN)
        assertEquals(AgentService.WAKE_LOCK_SCREEN, AgentService.wakeLockMode(context))
    }
}
