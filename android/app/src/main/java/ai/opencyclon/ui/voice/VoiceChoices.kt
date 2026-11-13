package ai.opencyclon.ui.voice

import ai.opencyclon.R
import ai.opencyclon.voice.AndroidSpeechInputEngine
import android.content.Context

/** The names of the recognizer choices and the sentence that says where the voice goes (F17.1, F17.1 step 4). */
object VoiceChoices {
    /** What Android offers: any usable recognizer, and whether it runs on the device. Tests replace it. */
    data class AndroidRecognizer(val available: Boolean, val onDevice: Boolean)

    @Volatile var androidRecognizer: (Context) -> AndroidRecognizer = { context ->
        AndroidRecognizer(AndroidSpeechInputEngine.available(context), AndroidSpeechInputEngine.onDeviceAvailable(context))
    }

    fun name(context: Context, choice: String): String = context.getString(
        when (choice) {
            VoiceFactory.PHONE_MODEL -> R.string.voice_choice_phone
            VoiceFactory.SERVICE -> R.string.voice_choice_service
            else -> R.string.voice_choice_android
        }
    )

    fun where(context: Context, choice: String): String = context.getString(
        when (choice) {
            VoiceFactory.PHONE_MODEL -> R.string.voice_where_phone
            VoiceFactory.SERVICE -> R.string.voice_where_service
            else -> if (androidRecognizer(context).onDevice) R.string.voice_where_android_on_device
            else R.string.voice_where_android
        }
    )
}
