package ai.opencyclon.assist

import ai.opencyclon.ui.voice.ListeningActivity
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssistTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class RecordingSession(context: Context) : AssistSession(context) {
        val opened = mutableListOf<Intent>()
        var closed = 0
        override fun open(intent: Intent) { opened += intent }
        override fun close() { closed++ }
    }

    @Test fun showingTheSessionStartsTheListeningScreenOnceAndHides() {
        val session = RecordingSession(context)
        session.onShow(null, 0)
        assertEquals(1, session.opened.size)
        assertEquals(ListeningActivity::class.java.name, session.opened.single().component?.className)
        assertTrue(session.opened.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(1, session.closed)
    }

    @Test fun stubRecognizerAnswersWithAnErrorAndNothingElse() {
        val service = Robolectric.buildService(StubRecognitionService::class.java).create().get()
        val callback = mockk<RecognitionService.Callback>(relaxed = true)
        service.onStartListening(Intent(), callback)
        service.onStopListening(callback)
        service.onCancel(callback)
        verify(exactly = 1) { callback.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) }
        confirmVerified(callback)
    }

    @Test fun stubRecognizerNeverOpensTheMicrophone() {
        val service = Robolectric.buildService(StubRecognitionService::class.java).create().get()
        service.onStartListening(Intent(), mockk(relaxed = true))
        // No recorder was created by the call ...
        assertTrue(context.getSystemService(android.media.AudioManager::class.java)!!.activeRecordingConfigurations.isEmpty())
        // ... and the source has no way to record.
        val source = listOf("src/main/java/ai/opencyclon/assist/StubRecognitionService.kt",
            "app/src/main/java/ai/opencyclon/assist/StubRecognitionService.kt").map(::File).first { it.exists() }.readText()
        for (word in listOf("AudioRecord", "MediaRecorder", "createSpeechRecognizer", "startRecording")) {
            assertFalse(word, source.contains(word))
        }
    }
}
