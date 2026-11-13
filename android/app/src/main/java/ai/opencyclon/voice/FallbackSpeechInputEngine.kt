package ai.opencyclon.voice

import android.util.Log

/**
 * Listens with the first engine of [makers]; when it reports [SpeechInputEngine.Listener.onUnavailable], the
 * next one takes over the same turn. Engines are built only when needed, so the phone's own recognizer never
 * makes the 64 MB model download start. The engine that worked stays in use for later turns.
 *
 * When the internet service was the one that could not listen ([VoiceErrors.SERVICE_UNREACHABLE]), the next
 * engine's turn starts with the notice [VoiceErrors.SERVICE_FELL_BACK]; when no engine is left, the screen is
 * told the service could not be reached rather than that voice is unavailable.
 */
class FallbackSpeechInputEngine(
    private val names: List<String>,
    private val makers: List<() -> SpeechInputEngine>,
) : SpeechInputEngine {
    init { require(names.size == makers.size && makers.isNotEmpty()) }

    private var index = 0
    private var current: SpeechInputEngine? = null
    private var destroyed = false
    private var serviceUnreachable = false

    /** The name of the engine listening now, or about to. */
    val activeName: String get() = names[index]

    override val showsPartials: Boolean get() = current?.showsPartials ?: true

    override fun startListening(listener: SpeechInputEngine.Listener) {
        if (destroyed) return
        start(listener)
    }

    private fun start(listener: SpeechInputEngine.Listener) {
        val engine = current ?: makers[index]().also { current = it }
        Log.i(TAG, "Listening with ${names[index]}")
        engine.startListening(object : SpeechInputEngine.Listener by listener {
            override fun onUnavailable(reason: String) {
                if (destroyed || engine !== current) return
                engine.destroy()
                current = null
                val fromService = reason.startsWith(VoiceErrors.SERVICE_UNREACHABLE)
                if (fromService) serviceUnreachable = true
                if (index + 1 < makers.size) {
                    Log.w(TAG, "${names[index]} is not available ($reason); trying ${names[index + 1]}")
                    index++
                    if (fromService) listener.onNotice(VoiceErrors.SERVICE_FELL_BACK)
                    start(listener)
                } else {
                    Log.w(TAG, "${names[index]} is not available ($reason); no other engine")
                    listener.onError(if (serviceUnreachable) VoiceErrors.SERVICE_UNREACHABLE else VoiceErrors.UNAVAILABLE)
                }
            }
        })
    }

    override fun stopListening() { current?.stopListening() }
    override fun finishNow() { current?.finishNow() }

    override fun destroy() {
        destroyed = true
        current?.destroy()
        current = null
    }

    companion object {
        const val TAG = "VoiceEngine"
    }
}
