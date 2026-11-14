package ai.opencyclone.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException

/**
 * The microphone as both recognizers inside the app open it: 16 kHz, mono, 16-bit, voice-recognition source,
 * a two-second buffer. Shared by [SherpaOnnxSpeechInputEngine] and [ServiceSpeechInputEngine].
 */
object MicRecorder {
    const val SAMPLE_RATE = 16000

    /** Samples read per block: 0.1 s. */
    const val READ_SIZE = 1600
    private const val BYTES_PER_SHORT = 2
    private const val BUFFER_SECONDS = 2
    private const val PCM_16BIT_SCALE = 32768.0f

    /** Creates an initialised recorder; throws when the microphone cannot be opened. */
    @SuppressLint("MissingPermission")
    fun create(): AudioRecord {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            throw IOException("Audio recording is unavailable")
        }
        // Two seconds: holds what is said while the recognizer is created (about 1.1 s on a Pixel 7).
        val bufferBytes = minBufferBytes.coerceAtLeast(SAMPLE_RATE * BYTES_PER_SHORT * BUFFER_SECONDS)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IOException("Audio recording initialization failed")
        }
        return recorder
    }

    /** Starts [recorder]; throws when Android does not let it record (another app holds the microphone). */
    fun start(recorder: AudioRecord) {
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IOException("Audio recording did not start")
        }
    }

    /**
     * Reads one block into [buffer]. Returns the number of samples, 0 when nothing arrived yet; throws on a
     * recorder error.
     */
    fun read(recorder: AudioRecord, buffer: ShortArray): Int {
        val read = recorder.read(buffer, 0, buffer.size)
        if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE ||
            read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR
        ) {
            throw IOException("Audio recording error $read")
        }
        return read.coerceAtLeast(0)
    }

    fun toFloats(buffer: ShortArray, count: Int): FloatArray =
        FloatArray(count) { buffer[it] / PCM_16BIT_SCALE }

    fun stop(recorder: AudioRecord?) {
        runCatching {
            if (recorder != null && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        }
    }

    fun release(recorder: AudioRecord?) {
        stop(recorder)
        runCatching { recorder?.release() }
    }
}

/**
 * Ends a turn by loudness alone, for a recognizer that gives no words while listening (the internet service):
 * [pauseMs] of quiet after speech, [nothingMs] when nothing was said, [maxMs] at most. The values are the
 * phone model's pause rules ([SherpaOnnxSpeechInputEngine.endpointConfig]). Pure: no clock, no Android.
 */
class EnergyPause(
    private val pauseMs: Long = (SherpaOnnxSpeechInputEngine.PAUSE_AFTER_WORDS_SECONDS * 1000).toLong(),
    private val nothingMs: Long = (SherpaOnnxSpeechInputEngine.NOTHING_HEARD_SECONDS * 1000).toLong(),
    private val maxMs: Long = (SherpaOnnxSpeechInputEngine.MAX_TURN_SECONDS * 1000).toLong(),
    // ponytail: a fixed level, the one the listening screen treats as speech; a loud room keeps the turn open
    // until the 20 s cap or "Stop".
    private val speechLevel: Float = TurnLog.SOUND_RMS,
) {
    private var elapsedMs = 0L
    private var quietMs = 0L

    /** True once a block at or above the speech level arrived. */
    var heardSpeech = false
        private set

    /** Adds one block of [blockMs] milliseconds at [level]; returns true when the turn should end now. */
    fun block(level: Float, blockMs: Long): Boolean {
        elapsedMs += blockMs
        if (level >= speechLevel) {
            heardSpeech = true
            quietMs = 0
        } else {
            quietMs += blockMs
        }
        return elapsedMs >= maxMs || (heardSpeech && quietMs >= pauseMs) || (!heardSpeech && elapsedMs >= nothingMs)
    }
}
