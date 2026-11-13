package ai.opencyclon.ui.start

import ai.opencyclon.voice.AsrModelDownloader
import ai.opencyclon.voice.SherpaModels
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Fetches the speech model for the voice step. Both callbacks run on the main thread. */
fun interface VoiceDownload {
    fun start(context: Context, progress: (percent: Int) -> Unit, done: (error: Throwable?) -> Unit)
}

/** The real download: the same files and the same downloader the listening screen uses. */
object ModelVoiceDownload : VoiceDownload {
    private const val TAG = "ModelVoiceDownload"

    // ponytail: a plain thread with no cancel. Leaving the step lets it finish in the background; the downloader's
    // lock makes the listening screen wait for it instead of starting a second download.
    override fun start(context: Context, progress: (Int) -> Unit, done: (Throwable?) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            var last = -1
            val error = runCatching {
                AsrModelDownloader.download(
                    model = SherpaModels.selected(app),
                    baseDir = app.filesDir,
                    listener = AsrModelDownloader.ProgressListener { read, total ->
                        val pct = StartPlan.percent(read, total)
                        if (pct != last) {
                            last = pct
                            main.post { progress(pct) }
                        }
                    },
                )
            }.exceptionOrNull()
            if (error != null) Log.w(TAG, "Speech model download failed", error)
            main.post { done(error) }
        }, "start-voice-download").start()
    }
}
