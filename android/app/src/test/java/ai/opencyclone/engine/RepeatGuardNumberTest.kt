package ai.opencyclone.engine

import ai.opencyclone.action.Action
import org.junit.Assert.*
import org.junit.Test

/** F18.1: the repeat guard compares element numbers with their labels, in addition to positions. */
class RepeatGuardNumberTest {
    private val screen = ScreenFingerprint(0L)

    @Test fun `the same number and label on an unchanged screen is blocked even when the position moved`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.Tap(100, 100, index = 3, label = "Next"), screen)
        assertTrue(guard.evaluate(Action.Tap(800, 800, index = 3, label = "Next"), screen) is ActionRepeatGuard.Decision.Block)
    }

    @Test fun `the same number with another label is allowed`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.Tap(100, 100, index = 3, label = "Next"), screen)
        assertSame(ActionRepeatGuard.Decision.Allow, guard.evaluate(Action.Tap(800, 800, index = 3, label = "Back"), screen))
    }

    @Test fun `positions are still compared`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.Tap(100, 100, index = 3, label = "Next"), screen)
        assertTrue(guard.evaluate(Action.Tap(110, 110), screen) is ActionRepeatGuard.Decision.Block)
    }
}
