package ai.opencyclone.testapp

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    private val counters by lazy { getSharedPreferences("counters", MODE_PRIVATE) }
    private var sent = 0
    private var pressed = 0
    private var touched = 0
    private var moved = 0
    private var slow = 0
    private var still = 0
    /** Delayed changes of the F18 screens (moving, slow, never still); cleared when the screen changes. */
    private val handler = Handler(Looper.getMainLooper())
    private var screen = 0
    private var pendingNotification = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sent = counters.getInt("sent", 0)
        pressed = counters.getInt("pressed", 0)
        touched = counters.getInt("touched", 0)
        moved = counters.getInt("moved", 0)
        slow = counters.getInt("slow", 0)
        still = counters.getInt("still", 0)
        pendingNotification = savedInstanceState?.getInt("pendingNotification") ?: 0
        writeCounters()
        showScreen(savedInstanceState?.getInt("screen") ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("screen", screen)
        outState.putInt("pendingNotification", pendingNotification)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Uses the platform Back button to return to the fixture menu")
    override fun onBackPressed() {
        if (screen != 0) showScreen(0) else super.onBackPressed()
    }

    private fun writeCounters() {
        counters.edit().putInt("sent", sent).putInt("pressed", pressed)
            .putInt("touched", touched).putInt("moved", moved).putInt("slow", slow).putInt("still", still).apply()
        val directory = checkNotNull(getExternalFilesDir(null))
        File(directory, "counters.txt").writeText(
            "sent=$sent pressed=$pressed touched=$touched moved=$moved slow=$slow still=$still\n")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showScreen(next: Int) {
        handler.removeCallbacksAndMessages(null)
        screen = next
        if (next == 3) {
            setContentView(TouchCanvas())
            return
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom)
                insets
            }
        }
        fun button(label: Int, action: () -> Unit) {
            layout.addView(Button(this).apply {
                setText(label)
                setOnClickListener { action() }
            })
        }
        when (next) {
            0 -> {
                button(R.string.labeled) { showScreen(1) }
                button(R.string.icon_only) { showScreen(2) }
                button(R.string.no_elements) { showScreen(3) }
                button(R.string.notify) { showScreen(4) }
                button(R.string.chat) { showScreen(5) }
                button(R.string.moving) { showScreen(6) }
                button(R.string.slow) { showScreen(7) }
                button(R.string.never_still) { showScreen(8) }
                button(R.string.read_only_field) { showScreen(9) }
            }
            // F18.1: one second after opening, "Move me" moves down and "Soon changed" gets another label.
            6 -> {
                val count = TextView(this).apply { text = getString(R.string.moved, moved) }
                val move = Button(this).apply {
                    setText(R.string.move_me)
                    setOnClickListener {
                        moved++
                        writeCounters()
                        count.text = getString(R.string.moved, moved)
                    }
                }
                val change = Button(this).apply { setText(R.string.soon_changed) }
                layout.addView(move)
                layout.addView(change)
                layout.addView(count)
                handler.postDelayed({
                    (move.layoutParams as LinearLayout.LayoutParams).topMargin =
                        (240 * resources.displayMetrics.density).toInt()
                    move.requestLayout()
                    change.setText(R.string.changed)
                }, 1000)
            }
            // F18.2: a tap on Load shows a spinner; content and a button appear 1.8 seconds later.
            7 -> {
                val count = TextView(this).apply { text = getString(R.string.slow_count, slow) }
                val spinner = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
                val late = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
                late.addView(TextView(this).apply { setText(R.string.loaded) })
                late.addView(Button(this).apply {
                    setText(R.string.slow_button)
                    setOnClickListener {
                        slow++
                        writeCounters()
                        count.text = getString(R.string.slow_count, slow)
                    }
                })
                button(R.string.load) {
                    spinner.visibility = View.VISIBLE
                    handler.postDelayed({
                        spinner.visibility = View.GONE
                        late.visibility = View.VISIBLE
                    }, 1800)
                }
                layout.addView(spinner)
                layout.addView(late)
                layout.addView(count)
            }
            // F18.2: a text that changes every 100 ms, forever, and a button.
            8 -> {
                val count = TextView(this).apply { text = getString(R.string.still_count, still) }
                val frame = TextView(this)
                var n = 0
                val tick = object : Runnable {
                    override fun run() {
                        frame.text = getString(R.string.frame, n++)
                        frame.translationX = ((n % 10) * 8 * resources.displayMetrics.density)
                        handler.postDelayed(this, 100)
                    }
                }
                layout.addView(frame)
                button(R.string.still_button) {
                    still++
                    writeCounters()
                    count.text = getString(R.string.still_count, still)
                }
                layout.addView(count)
                handler.post(tick)
            }
            // F18.3: a field that looks editable and discards every character typed into it.
            9 -> {
                layout.addView(EditText(this).apply {
                    setHint(R.string.type_here)
                    setSingleLine(true)
                    filters = arrayOf(InputFilter { _, _, _, _, _, _ -> "" })
                })
            }
            1 -> {
                val count = TextView(this).apply { text = getString(R.string.sent, sent) }
                fun send() {
                    sent++
                    writeCounters()
                    count.text = getString(R.string.sent, sent)
                }
                layout.addView(EditText(this).apply {
                    id = R.id.message
                    setHint(R.string.message)
                    setSingleLine(true)
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    setOnEditorActionListener { _, actionId, event ->
                        if (event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) send()
                            true
                        } else if (actionId == EditorInfo.IME_ACTION_SEND) {
                            send()
                            true
                        } else {
                            false
                        }
                    }
                })
                button(R.string.send) { send() }
                layout.addView(count)
            }
            2 -> {
                val count = TextView(this).apply { text = getString(R.string.pressed, pressed) }
                layout.addView(ImageButton(this).apply {
                    id = R.id.button1
                    setImageResource(android.R.drawable.ic_menu_send)
                    contentDescription = null
                    setOnClickListener {
                        pressed++
                        writeCounters()
                        count.text = getString(R.string.pressed, pressed)
                    }
                })
                layout.addView(count)
            }
            4 -> {
                button(R.string.post_notification) { requestNotification(1) }
                button(R.string.post_verification) { requestNotification(2) }
            }
            // A conversation with a readable title (an accessibility heading), so the approval card offers Approve.
            5 -> {
                layout.addView(TextView(this).apply {
                    setText(R.string.chat_title)
                    textSize = 22f
                    isAccessibilityHeading = true
                })
                val count = TextView(this).apply { text = getString(R.string.sent, sent) }
                button(R.string.send) {
                    sent++
                    writeCounters()
                    count.text = getString(R.string.sent, sent)
                }
                layout.addView(count)
            }
        }
        setContentView(layout)
        layout.requestApplyInsets()
    }

    private fun requestNotification(kind: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotification = kind
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            postNotification(kind)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            val kind = pendingNotification
            pendingNotification = 0
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && kind != 0) {
                postNotification(kind)
            }
        }
    }

    private fun postNotification(kind: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "gate_test", getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT
        ))
        val title = getString(if (kind == 1) R.string.promo_title else R.string.app_name)
        val text = if (kind == 1) getString(R.string.promo_text)
            else getString(R.string.verification_text)
        manager.notify(kind, Notification.Builder(this, "gate_test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .build())
    }

    private inner class TouchCanvas : View(this@MainActivity) {
        private val rectangle = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var startedInside = false

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            rectangle.set(w * 0.2f, h * 0.3f, w * 0.8f, h * 0.6f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.WHITE)
            paint.color = Color.RED
            canvas.drawRect(rectangle, paint)
            paint.color = Color.BLACK
            paint.textSize = 24 * resources.displayMetrics.scaledDensity
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(getString(R.string.touched, touched), width / 2f,
                rectangle.bottom + 48 * resources.displayMetrics.density, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startedInside = rectangle.contains(event.x, event.y)
                MotionEvent.ACTION_UP -> {
                    if (startedInside && rectangle.contains(event.x, event.y)) {
                        touched++
                        writeCounters()
                        invalidate()
                    }
                    startedInside = false
                }
                MotionEvent.ACTION_CANCEL -> startedInside = false
            }
            return true
        }
    }
}
