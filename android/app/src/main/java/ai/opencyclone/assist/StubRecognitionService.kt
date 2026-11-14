package ai.opencyclone.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The speech recognizer that the assistant role requires an assistant to name. The app recognises speech with
 * its bundled recognizer inside the listening screen, so this service only says "not supported" to any caller.
 * It never opens the microphone.
 *
 * ponytail: while the app holds the assistant role, the system makes this the default recognizer, so other
 * apps that use Android's default speech recognizer get this error until the owner picks another one.
 */
class StubRecognitionService : RecognitionService() {

    public override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
    }

    public override fun onCancel(listener: Callback?) = Unit

    public override fun onStopListening(listener: Callback?) = Unit
}
