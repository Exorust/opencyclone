package ai.opencyclone.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ends a task whose privileged connection does not come back: after the reconnect attempts failed or after
 * [limitMs] without a connection, whichever comes first. [onLost] runs at most once per drop.
 */
class ConnectionLoss(
    private val scope: CoroutineScope,
    private val limitMs: Long = LIMIT_MS,
    private val onLost: () -> Unit,
) {
    private var job: Job? = null

    /** The connection dropped while a task was running. A second call during the same drop changes nothing. */
    @Synchronized
    fun dropped() {
        if (job?.isActive == true) return
        job = scope.launch {
            delay(limitMs)
            expire()
        }
    }

    /** The reconnect attempts failed: ends the task now when a drop is being timed. */
    fun reconnectFailed() {
        if (disarm()) onLost()
    }

    /** The connection came back within the limit: nothing ends. */
    fun restored() {
        disarm()
    }

    private fun expire() {
        if (disarmFromTimer()) onLost()
    }

    @Synchronized
    private fun disarm(): Boolean {
        val armed = job?.isActive == true
        job?.cancel()
        job = null
        return armed
    }

    @Synchronized
    private fun disarmFromTimer(): Boolean {
        if (job == null) return false
        job = null
        return true
    }

    companion object {
        const val LIMIT_MS = 60_000L
    }
}
