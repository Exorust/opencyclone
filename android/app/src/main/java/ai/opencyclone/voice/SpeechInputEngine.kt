package ai.opencyclone.voice

interface SpeechInputEngine {
    interface Listener {
        fun onReady()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)

        /** [message] is one of [VoiceErrors] or free text; the screen turns it into a sentence. */
        fun onError(message: String)
        fun onEnd()

        /** Microphone level of one sample block: root mean square, 0 to 1. Called on the main thread. */
        fun onLevel(level: Float) {}

        /**
         * This engine cannot listen on this phone (no recognizer, missing language, missing libraries).
         * [FallbackSpeechInputEngine] tries the next engine; anything else treats it as an error.
         */
        fun onUnavailable(reason: String) = onError(VoiceErrors.UNAVAILABLE)

        /** Something the owner should know about this turn that is not an error, one of [VoiceErrors]. */
        fun onNotice(message: String) {}
    }

    /** False for an engine that gives the words only at the end of the turn (the internet service). */
    val showsPartials: Boolean get() = true

    fun startListening(listener: Listener)
    fun stopListening()
    fun destroy()

    /**
     * Ends the turn now: the current hypothesis arrives as [Listener.onFinalResult], or [Listener.onEnd]
     * when nothing was heard. The default only stops, for engines without a hypothesis.
     */
    fun finishNow() = stopListening()
}

/** Error messages the listening screen knows; each has its own sentence. */
object VoiceErrors {
    const val NO_PERMISSION = "Microphone permission is required"
    const val MIC_BUSY = "The microphone is in use"
    const val UNAVAILABLE = "Voice is not available on this phone"
    const val FAILED = "Listening failed"

    /** The internet service could not be reached (no connection, timeout, name not found). */
    const val SERVICE_UNREACHABLE = "The voice service could not be reached"

    /** The internet service answered 401 or 403. Never falls back: the owner must fix the key. */
    const val SERVICE_REFUSED = "The voice service refused the key"

    /** The internet service answered with another failure or a reply without text. */
    const val SERVICE_FAILED = "The voice service did not return text"

    /** Notice: the service was not reached and the recognizer on this phone took the turn. */
    const val SERVICE_FELL_BACK = "The recognizer on this phone was used"
}

/** Microphone level helpers. */
object MicLevel {
    /** Root mean square of the first [count] samples (each -1 to 1), clamped to 0 to 1. */
    fun rms(samples: FloatArray, count: Int = samples.size): Float {
        val n = count.coerceAtMost(samples.size)
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return Math.sqrt(sum / n).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Android's recognizer reports loudness in decibels, about -2 in a quiet room to 10 for loud speech.
     * Mapped onto the root-mean-square scale of [rms]: 0 dB and below is silence, 10 dB is 0.3.
     */
    fun fromDecibels(db: Float): Float = (db / 10f).coerceIn(0f, 1f) * 0.3f
}
