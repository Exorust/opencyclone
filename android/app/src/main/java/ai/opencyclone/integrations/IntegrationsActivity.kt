package ai.opencyclone.integrations

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.opencyclone.ui.screens.PlainScreen
import kotlinx.coroutines.launch

/** Lists connected MCP services and adds or removes them, in the plain look. Strings are constants below. */
class IntegrationsActivity : AppCompatActivity() {

    private lateinit var registry: ToolRegistry
    private lateinit var store: IntegrationStore
    private lateinit var screen: PlainScreen
    private lateinit var list: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var addressInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = ToolRegistry.getInstance(this)
        store = registry.store

        screen = PlainScreen.install(this, TITLE)
        screen.section(SERVICES_HEADER)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.rows.addView(list)

        screen.section(ADD_HEADER)
        nameInput = screen.fieldRow(NAME_LABEL, InputType.TYPE_CLASS_TEXT)
        addressInput = screen.fieldRow(ADDRESS_LABEL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, hint = ADDRESS_HINT)
        tokenInput = screen.fieldRow(TOKEN_LABEL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        listOf(nameInput, addressInput, tokenInput).forEach { it.isSingleLine = true }
        message = screen.sentence("").apply { tag = TAG_MESSAGE }
        connectButton = screen.primary(CONNECT) { connect() }

        prefill()
        lifecycleScope.launch {
            registry.restore()
            refresh()
        }
    }

    /** "Connect my notes service at <address>" opens this screen with name and address filled in; only the token is left. */
    private fun prefill() {
        val name = intent.getStringExtra(EXTRA_NAME)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        if (name.isNullOrBlank() && address.isNullOrBlank()) return
        name?.let { nameInput.setText(it) }
        address?.let { addressInput.setText(it) }
        tokenInput.requestFocus()
    }

    private fun connect() {
        val name = nameInput.text.toString().trim()
        val address = addressInput.text.toString().trim()
        val token = tokenInput.text.toString().ifEmpty { null }
        connectButton.isEnabled = false
        message.text = CONNECTING
        lifecycleScope.launch {
            when (val result = registry.connect(name, address, token)) {
                is ConnectResult.Ok -> {
                    message.text = CONNECTED_MESSAGE.format(name, result.toolCount)
                    nameInput.text.clear()
                    addressInput.text.clear()
                    tokenInput.text.clear()
                }
                is ConnectResult.Error -> message.text = "$CONNECT_FAILED: ${result.code}"
            }
            connectButton.isEnabled = true
            refresh()
        }
    }

    private suspend fun refresh() {
        val entries = store.all()
        list.removeAllViews()
        if (entries.isEmpty()) {
            screen.sentence(NO_SERVICES, parent = list)
            return
        }
        for (entry in entries) {
            val row = screen.textRow(
                title = entry.name,
                detail = ROW_DETAIL.format(entry.status, entry.toolCount),
                action = REMOVE,
                onAction = {
                    lifecycleScope.launch {
                        try {
                            registry.remove(entry.id)
                        } catch (_: Exception) {
                            message.text = REMOVE_FAILED
                        }
                        refresh()
                    }
                },
                parent = list,
            )
            row.findViewById<View>(ai.opencyclone.R.id.row_action).contentDescription = "$REMOVE ${entry.name}"
        }
    }

    companion object {
        const val EXTRA_NAME = "ai.opencyclone.integrations.NAME"
        const val EXTRA_ADDRESS = "ai.opencyclone.integrations.ADDRESS"
        const val TAG_MESSAGE = "integrations_message"
        const val TITLE = "Integrations"
        const val SERVICES_HEADER = "Connected services"
        const val ADD_HEADER = "Add a service"
        const val NAME_LABEL = "Name"
        const val ADDRESS_LABEL = "Address"
        const val ADDRESS_HINT = "https://..."
        const val TOKEN_LABEL = "Token"
        const val CONNECT = "Connect"
        const val CONNECTING = "Connecting…"
        const val CONNECTED_MESSAGE = "Connected %s with %d tools."
        const val CONNECT_FAILED = "Could not connect"
        const val NO_SERVICES = "No services are connected."
        const val ROW_DETAIL = "%s, %d tools"
        const val REMOVE = "Remove"
        const val REMOVE_FAILED = "Could not remove the service."
    }
}
