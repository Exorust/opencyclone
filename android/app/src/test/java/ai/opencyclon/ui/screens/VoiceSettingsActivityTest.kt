package ai.opencyclon.ui.screens

import ai.opencyclon.R
import ai.opencyclon.ui.PrivacyActivity
import ai.opencyclon.ui.voice.VoiceChoices
import ai.opencyclon.ui.voice.VoiceFactory
import ai.opencyclon.voice.SherpaModel
import ai.opencyclon.voice.SherpaModels
import ai.opencyclon.voice.VoiceServiceClient
import ai.opencyclon.voice.VoiceServiceConfig
import ai.opencyclon.voice.VoiceServiceStore
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSettingsActivityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var android = VoiceChoices.AndroidRecognizer(available = true, onDevice = true)
    private val installs = mutableListOf<String>()
    private var testResult: VoiceServiceClient.Result = VoiceServiceClient.Result.Text("")
    private val tested = mutableListOf<VoiceServiceConfig>()

    @Before fun setUp() {
        VoiceChoices.androidRecognizer = { android }
        VoiceSettingsActivity.runInBackground = { it() }
        VoiceSettingsActivity.installer = { model, dir, progress ->
            installs += model.id
            progress(model.downloadBytes / 2, model.downloadBytes)
            install(model, dir)
            progress(model.downloadBytes, model.downloadBytes)
        }
        VoiceSettingsActivity.tester = { config -> tested += config; testResult }
    }

    @After fun tearDown() {
        VoiceChoices.androidRecognizer = { c ->
            VoiceChoices.AndroidRecognizer(
                ai.opencyclon.voice.AndroidSpeechInputEngine.available(c),
                ai.opencyclon.voice.AndroidSpeechInputEngine.onDeviceAvailable(c),
            )
        }
        VoiceSettingsActivity.runInBackground = { work -> Thread(work).start() }
        VoiceSettingsActivity.tester = { VoiceServiceClient().test(it) }
        SherpaModels.ALL.forEach { it.dir(context.filesDir).deleteRecursively() }
    }

    private fun install(model: SherpaModel, base: File = context.filesDir) {
        model.dir(base).mkdirs()
        model.requiredFiles.forEach { File(model.dir(base), it).writeText("x") }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun start(): VoiceSettingsActivity = Robolectric.buildActivity(VoiceSettingsActivity::class.java).setup().get()

    private fun all(v: View): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun VoiceSettingsActivity.row(tag: String): View =
        all(window.decorView).first { it.id == R.id.row_text && it.tag == tag }

    private fun View.title() = findViewById<TextView>(R.id.row_title).text.toString()
    private fun View.detail() = findViewById<TextView>(R.id.row_detail).text.toString()
    private fun View.checked() = findViewById<View>(R.id.row_chevron).visibility == View.VISIBLE
    private fun View.action(): Button? = findViewById<Button>(R.id.row_action).takeIf { it.visibility == View.VISIBLE }
    private fun VoiceSettingsActivity.texts() = all(window.decorView).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text.toString() }
    private fun VoiceSettingsActivity.fields() = all(window.decorView).filterIsInstance<EditText>()

    @Test fun threeChoicesEachWithWhereTheVoiceGoesAndAndroidMarkedByDefault() {
        val a = start()
        assertEquals("Voice", a.findViewById<TextView>(R.id.screen_title).text.toString())
        val androidRow = a.row(VoiceFactory.ANDROID)
        assertEquals("Android's recognizer", androidRow.title())
        assertEquals(
            "Your voice goes to the recognizer that Android provides. On this phone it runs on the device.",
            androidRow.detail(),
        )
        assertTrue(androidRow.checked())
        assertEquals("Chosen", androidRow.findViewById<View>(R.id.row_chevron).contentDescription)
        assertEquals("On this phone", a.row(VoiceFactory.PHONE_MODEL).title())
        assertEquals("Your voice stays on this phone.", a.row(VoiceFactory.PHONE_MODEL).detail())
        assertFalse(a.row(VoiceFactory.PHONE_MODEL).checked())
        assertEquals("A service on the internet", a.row(VoiceFactory.SERVICE).title())
        assertEquals("Your voice goes to the service you choose.", a.row(VoiceFactory.SERVICE).detail())
        // No service fields until that choice is tapped.
        assertTrue(a.fields().isEmpty())
    }

    @Test fun withoutAnOnDeviceRecognizerTheSecondSentenceIsLeftOut() {
        android = VoiceChoices.AndroidRecognizer(available = true, onDevice = false)
        assertEquals("Your voice goes to the recognizer that Android provides.", start().row(VoiceFactory.ANDROID).detail())
    }

    @Test fun anUnavailableAndroidRecognizerCannotBeChosen() {
        android = VoiceChoices.AndroidRecognizer(available = false, onDevice = false)
        VoiceFactory.setChoice(context, VoiceFactory.PHONE_MODEL)
        val a = start()
        val row = a.row(VoiceFactory.ANDROID)
        assertEquals("Not available on this phone.", row.detail())
        assertFalse(row.isEnabled)
        row.performClick()
        assertEquals(VoiceFactory.PHONE_MODEL, VoiceFactory.choice(context))
        assertTrue(a.row(VoiceFactory.PHONE_MODEL).checked())
    }

    @Test fun tappingAChoiceMarksIt() {
        val a = start()
        a.row(VoiceFactory.PHONE_MODEL).performClick()
        assertEquals(VoiceFactory.PHONE_MODEL, VoiceFactory.choice(context))
        assertTrue(a.row(VoiceFactory.PHONE_MODEL).checked())
        assertFalse(a.row(VoiceFactory.ANDROID).checked())
    }

    @Test fun modelsShowNameLanguagesAndSizeAndTheInstalledOneInUseIsMarked() {
        install(SherpaModels.BILINGUAL)
        val a = start()
        val bilingual = a.row(SherpaModels.BILINGUAL.id)
        assertEquals("Bilingual, small", bilingual.title())
        assertEquals("Chinese and English. 64 MB.", bilingual.detail())
        assertTrue(bilingual.checked())
        assertNull(bilingual.action()) // in use: no Remove
        val english = a.row(SherpaModels.ENGLISH_SMALL.id)
        assertEquals("English. 45 MB.", english.detail())
        assertFalse(english.checked())
        assertEquals("Download", english.action()!!.text.toString())
    }

    @Test fun downloadingAModelAsksFirstShowsPercentThenMarksIt() {
        install(SherpaModels.BILINGUAL)
        val a = start()
        a.row(SherpaModels.ENGLISH_SMALL.id).action()!!.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Download 45 MB?", dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        assertEquals("Not now", dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf(SherpaModels.ENGLISH_SMALL.id), installs)
        assertEquals(SherpaModels.ENGLISH_SMALL, SherpaModels.selected(context))
        val english = a.row(SherpaModels.ENGLISH_SMALL.id)
        assertTrue(english.checked())
        // The bilingual model is no longer in use: it can be removed.
        val bilingual = a.row(SherpaModels.BILINGUAL.id)
        assertFalse(bilingual.checked())
        assertEquals("Remove", bilingual.action()!!.text.toString())
        bilingual.action()!!.performClick()
        assertFalse(File(context.filesDir, "asr/${SherpaModels.BILINGUAL.id}").exists())
        assertEquals("Download", a.row(SherpaModels.BILINGUAL.id).action()!!.text.toString())
    }

    @Test fun theProgressLineIsInPercent() {
        val a = start()
        VoiceSettingsActivity.installer = { _, _, progress -> progress(42, 100) }
        a.download(SherpaModels.ENGLISH_SMALL)
        idle()
        // The fake never installs: the line says the download failed.
        assertTrue(a.texts().contains("The download failed. Check the connection and try again."))
        VoiceSettingsActivity.runInBackground = { }
        a.download(SherpaModels.ENGLISH_SMALL)
        assertTrue(a.texts().contains("Downloading English, small. 0 percent."))
    }

    @Test fun theServiceShowsAddressModelKeyTheLineAndTest() {
        val a = start()
        a.row(VoiceFactory.SERVICE).performClick()
        // Not set up: not chosen yet.
        assertEquals(VoiceFactory.ANDROID, VoiceFactory.choice(context))
        val fields = a.fields()
        assertEquals(3, fields.size)
        assertEquals("https://api.openai.com/v1", fields[0].text.toString())
        assertEquals("gpt-4o-transcribe", fields[1].text.toString())
        assertTrue(a.texts().contains("Your voice is sent to this address."))
        assertTrue(a.texts().contains("Enter the address and the key, then tap Test."))
        assertEquals("Test", a.findViewById<Button>(R.id.screen_primary).text.toString())
    }

    @Test fun aTestThatAnswersChoosesTheServiceAndStoresTheKeySecretly() {
        val a = start()
        a.row(VoiceFactory.SERVICE).performClick()
        a.fields()[0].setText("https://voice.example.com/v1/")
        a.fields()[2].setText("sk-abc")
        a.findViewById<Button>(R.id.screen_primary).performClick()
        idle()
        assertTrue(a.texts().contains("The service answered."))
        assertEquals(VoiceFactory.SERVICE, VoiceFactory.choice(context))
        assertTrue(a.row(VoiceFactory.SERVICE).checked())
        assertEquals(VoiceServiceConfig("https://voice.example.com/v1", "gpt-4o-transcribe", "sk-abc"), tested.single())
        // The key is not in the plain preferences file.
        val plain = context.getSharedPreferences("voice", Context.MODE_PRIVATE).all.values.map { it.toString() }
        assertFalse(plain.any { it.contains("sk-abc") })
        assertEquals("sk-abc", VoiceServiceStore(context).load().key)
        // The key field is empty again and says a key is saved.
        assertEquals("", a.fields()[2].text.toString())
        assertEquals("A key is saved. Leave this empty to keep it.", a.fields()[2].hint.toString())
    }

    @Test fun refusedUnreachableAndFailedTestsSayWhyAndDoNotChoose() {
        val cases = mapOf(
            VoiceServiceClient.Result.Refused to "The voice service refused the key.",
            VoiceServiceClient.Result.Unreachable to "I could not reach the voice service.",
            VoiceServiceClient.Result.Failed("HTTP 500") to "The voice service did not return the words.",
        )
        for ((result, sentence) in cases) {
            testResult = result
            VoiceFactory.setChoice(context, VoiceFactory.ANDROID)
            ai.opencyclon.config.SecretStore(context).remove(VoiceServiceStore.SECRET_KEY)
            val a = start()
            a.row(VoiceFactory.SERVICE).performClick()
            a.fields()[2].setText("sk-abc")
            a.findViewById<Button>(R.id.screen_primary).performClick()
            idle()
            assertTrue(sentence, a.texts().contains(sentence))
            assertEquals(VoiceFactory.ANDROID, VoiceFactory.choice(context))
        }
    }

    @Test fun anAddressWithoutHttpsIsRefusedBeforeAnythingIsSent() {
        val a = start()
        a.row(VoiceFactory.SERVICE).performClick()
        a.fields()[0].setText("http://voice.example.com/v1")
        a.fields()[2].setText("sk-abc")
        a.findViewById<Button>(R.id.screen_primary).performClick()
        idle()
        assertTrue(a.texts().contains("The address must start with https://."))
        assertTrue(tested.isEmpty())
    }

    @Test fun thePrivacyScreenNamesTheChosenRecognizerAndWhereTheVoiceGoes() {
        fun privacyRow(): View {
            val p = Robolectric.buildActivity(PrivacyActivity::class.java).setup().get()
            return all(p.window.decorView).first {
                it.id == R.id.row_text && it.findViewById<TextView>(R.id.row_title).text.startsWith("Voice: ")
            }
        }
        var row = privacyRow()
        assertEquals("Voice: Android's recognizer", row.title())
        assertEquals("Your voice goes to the recognizer that Android provides. On this phone it runs on the device.", row.detail())
        VoiceFactory.setChoice(context, VoiceFactory.SERVICE)
        row = privacyRow()
        assertEquals("Voice: A service on the internet", row.title())
        assertEquals("Your voice goes to the service you choose.", row.detail())
        VoiceFactory.setChoice(context, VoiceFactory.PHONE_MODEL)
        assertEquals("Your voice stays on this phone.", privacyRow().detail())
    }
}
