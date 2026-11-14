package ai.opencyclone

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The privileged connection as the rest of the process sees it. [AgentService] writes it; the home page's live
 * state reads [notConnected].
 */
object AgentConnection {
    private const val PREFS = "agent_connection"
    private const val KEY_OWNER_DISCONNECTED = "owner_disconnected"

    private val _notConnected = MutableStateFlow(false)

    /** True while the owner has finished the start and no privileged connection exists. */
    val notConnected: StateFlow<Boolean> = _notConnected.asStateFlow()

    fun setNotConnected(value: Boolean) {
        _notConnected.value = value
    }

    /**
     * True after "Disconnect" in the settings, until the owner connects again from the pairing screen. While it is
     * set, nothing reconnects by itself: not the service at start, not the reconnect after a drop, not the home page.
     */
    fun ownerDisconnected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OWNER_DISCONNECTED, false)

    fun setOwnerDisconnected(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OWNER_DISCONNECTED, value).apply()
    }
}

/** True while the home page is resumed; the approval notification is then posted without sound or heads-up. */
object HomeForeground {
    @Volatile var resumed: Boolean = false
}
