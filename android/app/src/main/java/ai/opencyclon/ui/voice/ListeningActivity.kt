package ai.opencyclon.ui.voice

import ai.opencyclon.R
import ai.opencyclon.feed.FeedLocator
import ai.opencyclon.gate.DeviceLock
import ai.opencyclon.ui.theme.Wash
import ai.opencyclon.ui.theme.applySystemBarPadding
import ai.opencyclon.ui.theme.drawBehindSystemBars
import ai.opencyclon.ui.theme.setWash
import ai.opencyclon.ui.widget.SoundWaveView
import ai.opencyclon.ui.widget.WordRevealTextView
import ai.opencyclon.voice.SpeechInputEngine
import ai.opencyclon.voice.VoiceErrors
import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.addCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The voice screen (UI.md 5.2, 5.3, sections 9 and 10). Listening: live words, the turn ends at a pause,
 * on "Stop", or on a volume key, and the words go to [FeedLocator]. Speaking: the question is said aloud,
 * each word darkens as it is spoken, then the screen listens for the answer. Back sends nothing.
 * Shown over the lock screen; on a locked phone the words wait until the owner unlocks.
 */
class ListeningActivity : AppCompatActivity() {

    private enum class Mode { SPEAKING, LISTENING, ENDED }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: View
    private lateinit var topLine: TextView
    private lateinit var hint: TextView
    private lateinit var transcript: WordRevealTextView
    private lateinit var wave: SoundWaveView
    private lateinit var stopButton: Button
    private lateinit var timer: TextView
    private lateinit var permissionBlock: View

    private var mode = Mode.LISTENING
    private var engine: SpeechInputEngine? = null
    private var speech: SpeechOut? = null
    private var askedPermission = false
    private var answerTo: String? = null // the question the owner answers, shown in the hint line

    // Listening
    private var lastPartial = ""
    private var heardSpeech = false
    private var micOffNoted = false
    private var listenStartedAt = 0L
    private var showingProgress = false
    /** The turn fell back from the internet service; the sentence about it is on the screen. */
    private var noticeShown = false
    private var pendingText: String? = null
    private var sent = false

    // Speaking
    private var wordRanges: List<IntRange> = emptyList()
    private var questionLength = 0
    private var spokenWord = -1
    private var speechStartedAt = 0L
    private var wordStartedAt = 0L
    private var gotRange = false
    private var fallbackTiming = false
    private var speechRate = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        drawBehindSystemBars()
        setContentView(R.layout.activity_listening)
        root = findViewById(R.id.voice_root)
        topLine = findViewById(R.id.voice_top_line)
        hint = findViewById(R.id.voice_hint)
        transcript = findViewById(R.id.voice_transcript)
        wave = findViewById(R.id.voice_wave)
        stopButton = findViewById(R.id.voice_stop)
        timer = findViewById(R.id.voice_timer)
        permissionBlock = findViewById(R.id.voice_permission)

        root.setWash(Wash.Voice)
        applySystemBarPadding(root)
        findViewById<View>(R.id.voice_dot).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@ListeningActivity, R.color.ink))
        }
        askedPermission = savedInstanceState?.getBoolean(KEY_ASKED) ?: false

        stopButton.setOnClickListener { endByOwner() }
        root.setOnClickListener { if (mode == Mode.SPEAKING) interruptSpeech() }
        findViewById<View>(R.id.voice_open_settings).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
        onBackPressedDispatcher.addCallback(this) { cancel() }
        wave.setFlat()

        val question = intent.getStringExtra(EXTRA_QUESTION)
        if (!question.isNullOrBlank()) startSpeaking(question.trim(), intent.getStringExtra(EXTRA_OWNER_WORDS))
        else startListening()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ASKED, askedPermission)
    }

    override fun onResume() {
        super.onResume()
        // Back from the app's settings with the microphone allowed.
        if (permissionBlock.visibility == View.VISIBLE && hasMicPermission()) startListening()
        sendPendingIfUnlocked()
    }

    override fun onStop() {
        super.onStop()
        // The microphone and the speech never run behind another screen or a dark screen.
        if (!isChangingConfigurations && (mode == Mode.LISTENING || mode == Mode.SPEAKING)) cancel()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine?.destroy()
        engine = null
        speech?.shutdown()
        speech = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (mode == Mode.LISTENING &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            endByOwner()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC || mode != Mode.LISTENING) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginEngine() else showNoPermission()
    }

    // ---- Speaking -------------------------------------------------------------------------------

    private fun startSpeaking(question: String, ownerWords: String?) {
        mode = Mode.SPEAKING
        answerTo = question
        topLine.setText(R.string.voice_speaking_top)
        val owner = ownerWords?.trim().orEmpty()
        hint.text = if (owner.isEmpty()) "" else getString(R.string.voice_quoted, owner)
        hint.visibility = if (owner.isEmpty()) View.GONE else View.VISIBLE
        stopButton.visibility = View.GONE
        timer.visibility = View.GONE
        transcript.setTextAppearance(R.style.TextAppearance_App_Transcript_Speaking)
        transcript.showAll(question)
        questionLength = question.length
        wordRanges = WordRevealTextView.wordRanges(question)
        transcript.markUnconfirmed(0, questionLength)

        val out = VoiceFactory.speechOut(this)
        if (out == null) {
            speechEnded()
            return
        }
        speech = out
        speechRate = out.rate
        out.speak(question, object : SpeechOut.Listener {
            override fun onStart() {
                if (mode != Mode.SPEAKING) return
                speechStartedAt = SystemClock.uptimeMillis()
                handler.postDelayed(startFallback, SpeechTiming.RANGE_WAIT_MS)
                handler.post(speakingTick)
            }

            override fun onRange(charStart: Int) {
                if (mode != Mode.SPEAKING) return
                gotRange = true
                fallbackTiming = false
                setSpokenWord(SpeechTiming.wordIndexForChar(wordRanges, charStart))
            }

            override fun onDone() = speechEnded()
            override fun onUnavailable() = speechEnded()
        })
    }

    private val startFallback = Runnable {
        if (mode == Mode.SPEAKING && !gotRange) fallbackTiming = true
    }

    private val speakingTick: Runnable = object : Runnable {
        override fun run() {
            if (mode != Mode.SPEAKING) return
            val now = SystemClock.uptimeMillis()
            if (fallbackTiming) {
                setSpokenWord(SpeechTiming.fallbackWordIndex(now - speechStartedAt, wordRanges.size, speechRate))
            }
            if (spokenWord in wordRanges.indices) {
                wave.setLevel(
                    SpeechTiming.syntheticLevel(now - wordStartedAt, SpeechTiming.msPerWord(speechRate), spokenWord)
                )
            }
            handler.postDelayed(this, SPEAKING_TICK_MS)
        }
    }

    /** Word [index] is being spoken: it and every word before it are ink. */
    private fun setSpokenWord(index: Int) {
        if (index == spokenWord) return
        spokenWord = index
        wordStartedAt = SystemClock.uptimeMillis()
        val spokenEnd = if (index >= wordRanges.size) questionLength else wordRanges[index].last + 1
        transcript.markUnconfirmed(spokenEnd, questionLength)
    }

    private fun interruptSpeech() {
        speech?.stop()
        speechEnded()
    }

    /** The speech ended, was interrupted, or could not start: listen for the answer. */
    private fun speechEnded() {
        if (mode != Mode.SPEAKING) return
        handler.removeCallbacks(startFallback)
        handler.removeCallbacks(speakingTick)
        transcript.clearUnconfirmed()
        wave.setFlat()
        startListening()
    }

    // ---- Listening ------------------------------------------------------------------------------

    private fun startListening() {
        mode = Mode.LISTENING
        topLine.setText(R.string.voice_listening_top)
        hint.visibility = View.VISIBLE
        hint.text = answerTo ?: normalHint()
        permissionBlock.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        timer.visibility = View.VISIBLE
        timer.text = elapsedText(0)
        transcript.visibility = View.VISIBLE
        transcript.setTextAppearance(R.style.TextAppearance_App_Transcript)
        transcript.showAll("")
        wave.setFlat()
        when {
            hasMicPermission() -> beginEngine()
            !askedPermission -> {
                askedPermission = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            }
            else -> showNoPermission()
        }
    }

    private fun normalHint(): String =
        if (!hintShownThisProcess) {
            hintShownThisProcess = true
            getString(R.string.voice_hint_first, getString(R.string.app_name))
        } else {
            getString(R.string.voice_hint)
        }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun showNoPermission() {
        // F1.10: the home page gets the sentence and an "Allow" row, once for this screen.
        if (!micOffNoted && FeedLocator.isReady) {
            micOffNoted = true
            FeedLocator.commands.microphoneOff()
        }
        mode = Mode.ENDED
        transcript.visibility = View.GONE
        stopButton.visibility = View.GONE
        timer.visibility = View.GONE
        permissionBlock.visibility = View.VISIBLE
        wave.setFlat()
    }

    private fun beginEngine() {
        val e = engine ?: VoiceFactory.engine(this) { read, total -> onDownload(read, total) }.also { engine = it }
        lastPartial = ""
        heardSpeech = false
        listenStartedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(timerTick)
        handler.postDelayed(timerTick, 1000)
        noticeShown = false
        e.startListening(engineListener)
        // An engine without words while listening (the internet service) says when the words come.
        if (!e.showsPartials && answerTo == null) hint.setText(R.string.voice_service_hint)
    }

    private val timerTick: Runnable = object : Runnable {
        override fun run() {
            if (mode != Mode.LISTENING) return
            timer.text = elapsedText(SystemClock.uptimeMillis() - listenStartedAt)
            handler.postDelayed(this, 1000)
        }
    }

    private fun elapsedText(ms: Long): String {
        val s = (ms / 1000).toInt()
        return getString(R.string.voice_elapsed, s / 60, s % 60)
    }

    private fun onDownload(read: Long, total: Long) {
        if (mode != Mode.LISTENING || total <= 0) return
        showingProgress = true
        hint.text = getString(R.string.voice_preparing, (read * 100 / total).toInt().coerceIn(0, 100))
    }

    private val engineListener = object : SpeechInputEngine.Listener {
        override fun onReady() {
            if (mode != Mode.LISTENING) return
            listenStartedAt = SystemClock.uptimeMillis()
            timer.text = elapsedText(0)
            if (showingProgress) {
                showingProgress = false
                hint.text = answerTo ?: getString(R.string.voice_hint)
            }
        }

        override fun onPartialResult(text: String) {
            if (mode != Mode.LISTENING) return
            heardSpeech = true
            val split = TranscriptFormatter.split(lastPartial, text)
            if (split.text.isEmpty()) return
            lastPartial = split.text
            showTranscript(split, cursor = true)
        }

        override fun onLevel(level: Float) {
            if (mode != Mode.LISTENING) return
            if (debuggable) debugLevel(level)
            if (!heardSpeech && level < SPEECH_RMS) return
            heardSpeech = true
            wave.setLevel(SoundWaveView.fromRms(level))
        }

        override fun onFinalResult(text: String) {
            Log.i(TAG, "Turn ended: final result, ${text.length} characters")
            endTurn(text)
        }

        override fun onEnd() {
            Log.i(TAG, "Turn ended: no final result, last partial ${lastPartial.length} characters")
            endTurn(lastPartial)
        }

        override fun onNotice(message: String) {
            if (mode != Mode.LISTENING || message != VoiceErrors.SERVICE_FELL_BACK) return
            noticeShown = true
            showingProgress = false
            hint.visibility = View.VISIBLE
            hint.setText(R.string.voice_service_fell_back)
        }

        override fun onError(message: String) {
            if (mode != Mode.LISTENING) return
            Log.w(TAG, "Listening failed: $message")
            if (message == VoiceErrors.NO_PERMISSION) {
                handler.removeCallbacks(timerTick)
                engine?.stopListening()
                showNoPermission()
                return
            }
            endTurn("", errorSentence(message))
        }
    }

    // Debug builds: the loudest microphone level of each second, so a flat wave can be told from a deaf microphone.
    private val debuggable by lazy { (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private var debugLevelMax = 0f
    private var debugLevelAt = 0L
    private fun debugLevel(level: Float) {
        debugLevelMax = maxOf(debugLevelMax, level)
        val now = SystemClock.uptimeMillis()
        if (now - debugLevelAt < 1000) return
        Log.d(TAG, "Level, loudest in the last second: %.3f".format(debugLevelMax))
        debugLevelAt = now
        debugLevelMax = 0f
    }

    private fun showTranscript(split: TranscriptFormatter.Split, cursor: Boolean) {
        val text = SpannableStringBuilder(split.text)
        if (cursor) {
            text.append(CURSOR_CHAR)
            text.setSpan(CursorSpan(resources.displayMetrics.density, transcript.currentTextColor),
                text.length - 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        transcript.showAll(text)
        transcript.markUnconfirmed(split.confirmedEnd, split.text.length)
    }

    /** "Stop" or a volume key: the engine returns what it heard; a slow engine is not waited for. */
    private fun endByOwner() {
        if (mode != Mode.LISTENING) return
        val e = engine
        if (e == null) {
            endTurn(lastPartial)
            return
        }
        e.finishNow()
        handler.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
    }

    private val stopTimeout = Runnable { endTurn(lastPartial) }

    private fun endTurn(text: String, @StringRes emptyMessage: Int = R.string.voice_heard_nothing) {
        if (mode != Mode.LISTENING) return
        mode = Mode.ENDED
        handler.removeCallbacks(stopTimeout)
        handler.removeCallbacks(timerTick)
        engine?.stopListening()
        wave.setFlat()
        stopButton.isEnabled = false
        val words = text.trim()
        if (words.isEmpty()) {
            Log.i(TAG, "Nothing to send: ${getString(emptyMessage)}")
            // F1.3, F1.11: the sentence stays on this screen and is also one sentence in the thread.
            if (FeedLocator.isReady) FeedLocator.commands.note(getString(emptyMessage))
            closeWithMessage(emptyMessage)
            return
        }
        showTranscript(TranscriptFormatter.final(words), cursor = false)
        if (DeviceLock.isUnlocked(this)) {
            send(words)
        } else {
            pendingText = words
            hint.visibility = View.VISIBLE
            hint.setText(R.string.voice_unlock_to_send)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = sendPendingIfUnlocked()
                })
        }
    }

    private fun sendPendingIfUnlocked() {
        val words = pendingText ?: return
        if (DeviceLock.isUnlocked(this)) send(words)
    }

    private fun send(words: String) {
        pendingText = null
        if (sent) return
        sent = true
        // ponytail: until lane N4 sets the real feed at process start, there is nowhere to send to.
        if (!FeedLocator.isReady) {
            closeWithMessage(R.string.voice_not_ready)
            return
        }
        FeedLocator.commands.submit(words)
        Log.i(TAG, "Sent ${words.length} characters to the feed")
        // The fallback sentence stays readable for a moment before the screen closes.
        if (noticeShown) handler.postDelayed({ finish() }, MESSAGE_MS) else finish()
    }

    private fun closeWithMessage(@StringRes message: Int) {
        mode = Mode.ENDED
        transcript.visibility = View.VISIBLE
        transcript.showAll(getString(message))
        handler.postDelayed({ finish() }, MESSAGE_MS)
    }

    /** Back: nothing is sent. */
    private fun cancel() {
        mode = Mode.ENDED
        pendingText = null
        handler.removeCallbacksAndMessages(null)
        engine?.stopListening()
        speech?.stop()
        finish()
    }

    /** A thin cursor after the last word, the height of the text. */
    private class CursorSpan(density: Float, color: Int) : ReplacementSpan() {
        private val gap = 2 * density
        private val width = 1.5f * density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        override fun getSize(p: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (gap + width).toInt() + 1

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, p: Paint,
        ) {
            canvas.drawRect(x + gap, y + p.ascent(), x + gap + width, y + p.descent(), paint)
        }
    }

    companion object {
        private const val TAG = "ListeningActivity"

        /** The sentence for an engine error ([VoiceErrors]); the owner always sees why listening ended. */
        @StringRes
        internal fun errorSentence(message: String): Int = when (message) {
            VoiceErrors.MIC_BUSY -> R.string.voice_mic_busy
            VoiceErrors.UNAVAILABLE -> R.string.voice_unavailable
            VoiceErrors.SERVICE_UNREACHABLE -> R.string.voice_service_unreachable
            VoiceErrors.SERVICE_REFUSED -> R.string.voice_service_refused
            VoiceErrors.SERVICE_FAILED -> R.string.voice_service_failed
            else -> R.string.voice_failed
        }
        const val EXTRA_QUESTION = "voice_question"
        const val EXTRA_OWNER_WORDS = "voice_owner_words"
        private const val KEY_ASKED = "asked_mic"
        private const val REQUEST_MIC = 41
        private const val CURSOR_CHAR = "​"
        internal const val MESSAGE_MS = 2000L
        internal const val STOP_TIMEOUT_MS = 1500L
        private const val SPEAKING_TICK_MS = 50L
        /** Below this microphone level, before the first word, the wave stays a dotted line. */
        private const val SPEECH_RMS = 0.02f

        /** The long hint is shown once in a process, the short one after that. */
        @Volatile internal var hintShownThisProcess = false

        /** Opens the screen listening for a request. */
        fun listenIntent(context: Context): Intent =
            Intent(context, ListeningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Opens the screen saying [question] aloud, then listening for the answer. */
        fun speakIntent(context: Context, question: String, ownerWords: String? = null): Intent =
            listenIntent(context)
                .putExtra(EXTRA_QUESTION, question)
                .apply { if (ownerWords != null) putExtra(EXTRA_OWNER_WORDS, ownerWords) }
    }
}
