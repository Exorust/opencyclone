package ai.opencyclone.voice

import android.util.Log

/**
 * Follows one listening turn block by block: which partial results are new, and the first non-silent block,
 * the first partial result, and the pause, each logged once. No audio and no words reach the log, only lengths.
 */
internal class TurnLog(private val log: (String) -> Unit = { Log.i(SherpaOnnxSpeechInputEngine.TAG, it) }) {
    data class Step(val partialChanged: Boolean, val endpoint: Boolean)

    private var blocks = 0
    private var lastPartial = ""
    private var heardSound = false
    private var gotPartial = false

    fun block(level: Float, partial: String, endpoint: Boolean): Step {
        blocks++
        if (!heardSound && level >= SOUND_RMS) {
            heardSound = true
            log("First non-silent block: #$blocks, level %.3f".format(level))
        }
        val changed = partial.isNotEmpty() && partial != lastPartial
        if (changed) {
            lastPartial = partial
            if (!gotPartial) {
                gotPartial = true
                log("First partial result: block #$blocks, ${partial.length} characters")
            }
        }
        if (endpoint) {
            log("Pause detected: block #$blocks, heard sound=$heardSound, partial=${partial.length} characters")
        }
        return Step(changed, endpoint)
    }

    companion object {
        /** The level the listening screen treats as speech (ListeningActivity.SPEECH_RMS). */
        const val SOUND_RMS = 0.02f
    }
}
