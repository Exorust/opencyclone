package ai.opencyclon.ui.screens

import ai.opencyclon.R
import ai.opencyclon.ui.start.StartPlan
import ai.opencyclon.ui.voice.VoiceChoices
import ai.opencyclon.ui.voice.VoiceFactory
import ai.opencyclon.voice.AsrModelDownloader
import ai.opencyclon.voice.SherpaModel
import ai.opencyclon.voice.SherpaModels
import ai.opencyclon.voice.VoiceServiceClient
import ai.opencyclon.voice.VoiceServiceStore
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * "Voice" (F17.1 to F17.3): the three recognizers with the sentence that says where the voice goes, the models
 * on this phone, and the internet service's address, model, and key with "Test". Plain look of UI.md 5.12.
 */
class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var screen: PlainScreen
    private lateinit var store: VoiceServiceStore

    /** The service fields are shown once the owner taps that choice, or when it is the choice. */
    @VisibleForTesting internal var showService = false
    @VisibleForTesting internal var serviceStatus: String? = null
    @VisibleForTesting internal var modelStatus: String? = null
    private var downloadingId: String? = null

    private var addressField: EditText? = null
    private var modelField: EditText? = null
    private var keyField: EditText? = null
    private var address = ""
    private var serviceModel = ""
    private var keyTyped = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = PlainScreen.install(this, getString(R.string.voice_settings_title))
        store = VoiceServiceStore(this)
        store.load().let { address = it.address; serviceModel = it.model }
        showService = VoiceFactory.choice(this) == VoiceFactory.SERVICE
        render()
    }

    override fun onPause() {
        saveService()
        super.onPause()
    }

    /** Stores the fields; a typed key goes to the secret store and leaves the field. */
    private fun saveService() {
        readFields()
        store.save(address, serviceModel, keyTyped.takeIf { it.isNotBlank() })
        keyTyped = ""
        keyField?.setText("")
    }

    private fun readFields() {
        addressField?.let { address = it.text.toString() }
        modelField?.let { serviceModel = it.text.toString() }
        keyField?.let { keyTyped = it.text.toString() }
    }

    @VisibleForTesting internal fun render() {
        readFields()
        screen.rows.removeAllViews()
        addressField = null
        modelField = null
        keyField = null
        val choice = VoiceFactory.choice(this)
        val android = VoiceChoices.androidRecognizer(this)

        choiceRow(
            VoiceFactory.ANDROID, choice,
            if (android.available) VoiceChoices.where(this, VoiceFactory.ANDROID) else getString(R.string.voice_android_unavailable),
            enabled = android.available,
        )
        choiceRow(VoiceFactory.PHONE_MODEL, choice, VoiceChoices.where(this, VoiceFactory.PHONE_MODEL))
        choiceRow(VoiceFactory.SERVICE, choice, VoiceChoices.where(this, VoiceFactory.SERVICE))

        renderModels()
        if (showService) renderService()
    }

    private fun choiceRow(value: String, chosen: String, sentence: String, enabled: Boolean = true) {
        val row = screen.textRow(
            VoiceChoices.name(this, value), detail = sentence,
            onClick = if (enabled) ({ choose(value) }) else null,
        )
        row.tag = value
        row.isEnabled = enabled
        if (!enabled) row.alpha = DISABLED_ALPHA
        mark(row, value == chosen)
    }

    /** The check glyph at the end of a row, in the place of the chevron. */
    private fun mark(row: View, chosen: Boolean) {
        row.findViewById<ImageView>(R.id.row_chevron).apply {
            setImageResource(R.drawable.ic_check_16)
            contentDescription = getString(R.string.voice_chosen)
            visibility = if (chosen) View.VISIBLE else View.GONE
        }
    }

    private fun choose(value: String) {
        when (value) {
            VoiceFactory.SERVICE -> {
                showService = true
                saveService()
                val config = store.load()
                // Chosen only when it is set up; otherwise the fields are shown and "Test" chooses it.
                if (config.usable(VoiceServiceStore.debugBuild(this))) VoiceFactory.setChoice(this, value)
                else serviceStatus = getString(R.string.voice_service_need_setup)
            }
            else -> VoiceFactory.setChoice(this, value)
        }
        render()
    }

    // ── Models on this phone ───────────────────────────────────────────────

    private fun renderModels() {
        screen.section(getString(R.string.voice_models_heading))
        val inUse = SherpaModels.selected(this).id
        for (model in SherpaModels.ALL) {
            val installed = model.installed(filesDir)
            val detail = getString(R.string.voice_model_detail, model.languages, megabytes(model.downloadBytes))
            val action = when {
                downloadingId != null -> null
                !installed -> getString(R.string.voice_model_download)
                model.id != inUse -> getString(R.string.voice_model_remove)
                else -> null
            }
            val row = screen.textRow(
                model.name, detail = detail, action = action,
                onAction = { if (installed) remove(model) else confirmDownload(model) },
                onClick = if (installed && model.id != inUse) ({ useModel(model) }) else null,
            )
            row.tag = model.id
            mark(row, installed && model.id == inUse)
        }
        modelStatus?.let { screen.caption(it) }
    }

    private fun useModel(model: SherpaModel) {
        SherpaModels.select(this, model.id)
        modelStatus = null
        render()
    }

    private fun remove(model: SherpaModel) {
        if (SherpaModels.remove(filesDir, model, SherpaModels.selected(this).id)) modelStatus = null
        render()
    }

    @VisibleForTesting internal fun confirmDownload(model: SherpaModel) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_model_confirm, megabytes(model.downloadBytes)))
            .setPositiveButton(R.string.voice_model_download) { _, _ -> download(model) }
            .setNegativeButton(R.string.ui_not_now, null)
            .show()
    }

    @VisibleForTesting internal fun download(model: SherpaModel) {
        downloadingId = model.id
        modelStatus = getString(R.string.voice_model_progress, model.name, 0)
        render()
        var last = -1
        runInBackground {
            val error = runCatching {
                installer(model, filesDir) { read, total ->
                    val pct = StartPlan.percent(read, total)
                    if (pct != last) {
                        last = pct
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            modelStatus = getString(R.string.voice_model_progress, model.name, pct)
                            render()
                        }
                    }
                }
            }.exceptionOrNull()
            runOnUiThread {
                downloadingId = null
                if (error != null) Log.w(TAG, "Model download failed", error)
                if (isDestroyed) return@runOnUiThread
                if (error == null && model.installed(filesDir)) {
                    SherpaModels.select(this, model.id)
                    modelStatus = null
                } else {
                    modelStatus = getString(R.string.voice_model_failed)
                }
                render()
            }
        }
    }

    // ── A service on the internet ──────────────────────────────────────────

    private fun renderService() {
        screen.section(getString(R.string.voice_service_heading))
        addressField = screen.fieldRow(
            getString(R.string.voice_service_address), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, address,
        )
        modelField = screen.fieldRow(getString(R.string.voice_service_model), InputType.TYPE_CLASS_TEXT, serviceModel)
        keyField = screen.fieldRow(
            getString(R.string.voice_service_key), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            keyTyped, hint = if (store.hasKey()) getString(R.string.voice_service_key_saved) else null,
        )
        screen.caption(getString(R.string.voice_service_sent))
        serviceStatus?.let { screen.sentence(it) }
        screen.primary(getString(R.string.voice_service_test)) { test() }
    }

    @VisibleForTesting internal fun test() {
        readFields()
        if (!VoiceServiceStore.addressAllowed(address, VoiceServiceStore.debugBuild(this))) {
            serviceStatus = getString(R.string.voice_service_bad_address)
            render()
            return
        }
        saveService()
        val config = store.load()
        if (config.key.isBlank()) {
            serviceStatus = getString(R.string.voice_service_need_setup)
            render()
            return
        }
        serviceStatus = getString(R.string.voice_service_testing)
        render()
        runInBackground {
            val result = tester(config)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                serviceStatus = getString(sentenceFor(result))
                if (result is VoiceServiceClient.Result.Text) VoiceFactory.setChoice(this, VoiceFactory.SERVICE)
                render()
            }
        }
    }

    companion object {
        private const val TAG = "VoiceSettings"
        private const val DISABLED_ALPHA = 0.5f

        /** Runs network and download work; tests run it at once. */
        @VisibleForTesting internal var runInBackground: (() -> Unit) -> Unit = { work -> Thread(work, "voice-settings").start() }
        @VisibleForTesting internal var tester: (ai.opencyclon.voice.VoiceServiceConfig) -> VoiceServiceClient.Result = { VoiceServiceClient().test(it) }
        @VisibleForTesting internal var installer: (SherpaModel, File, (Long, Long) -> Unit) -> Unit = { model, dir, progress ->
            AsrModelDownloader.download(model, dir) { read, total -> progress(read, total) }
        }

        fun sentenceFor(result: VoiceServiceClient.Result): Int = when (result) {
            is VoiceServiceClient.Result.Text -> R.string.voice_service_answered
            VoiceServiceClient.Result.Refused -> R.string.voice_service_refused
            VoiceServiceClient.Result.Unreachable -> R.string.voice_service_unreachable
            is VoiceServiceClient.Result.Failed -> R.string.voice_service_failed
        }

        fun megabytes(bytes: Long): Int = ((bytes + 500_000) / 1_000_000).toInt()

        fun intent(context: Context): Intent = Intent(context, VoiceSettingsActivity::class.java)
    }
}
