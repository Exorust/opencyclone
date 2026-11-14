package ai.opencyclone.assist

import ai.opencyclone.ui.voice.ListeningActivity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/** Creates one [AssistSession] each time the owner holds the power button. */
class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * A session without a window of its own: it starts the listening screen (which handles the lock screen
 * itself: it shows over it and sends only after unlock) and hides at once. The activity is the screen.
 */
open class AssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        open(ListeningActivity.listenIntent(context))
        close()
    }

    /** Starts [intent] as the assistant's activity, above other apps and over the lock screen. */
    internal open fun open(intent: Intent) {
        try {
            startAssistantActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "startAssistantActivity failed, starting normally", e)
            try {
                context.startActivity(intent)
            } catch (e2: RuntimeException) {
                Log.w(TAG, "Cannot open the listening screen", e2)
            }
        }
    }

    internal open fun close() = hide()

    private companion object {
        const val TAG = "AssistSession"
    }
}
