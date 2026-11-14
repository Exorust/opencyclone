package ai.opencyclone.assist

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * The service that makes the app eligible for the assistant role. While the app holds the role, holding the
 * power button shows a session of [AssistSessionService], which opens the listening screen.
 */
class AssistService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // The listening screen needs nothing from the app in front: ask the system not to send its screen
        // content or a screenshot to the session.
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}
