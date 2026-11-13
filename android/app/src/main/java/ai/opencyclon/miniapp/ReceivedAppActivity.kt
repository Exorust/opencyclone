package ai.opencyclon.miniapp

import ai.opencyclon.R
import ai.opencyclon.db.AppDatabase
import ai.opencyclon.miniapp.ReceivedAppChecks.Finding
import ai.opencyclon.ui.theme.Wash
import ai.opencyclon.ui.theme.applySystemBarPadding
import ai.opencyclon.ui.theme.drawBehindSystemBars
import ai.opencyclon.ui.theme.setWash
import ai.opencyclon.ui.widget.AppIcons
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Opens an app file that someone sent (F9, the recipient's side). Reached only through the system's
 * "Open with" or share chooser, because Android does not let us read a file inside another app.
 * The file is read once, checked by [ReceivedAppChecks], and written nowhere until the owner taps Open;
 * then it is stored as a new built app and shown by [MiniAppActivity].
 */
class ReceivedAppActivity : AppCompatActivity() {

    /** The result of reading the intent: the text and how it came, or the reason it could not be read. */
    @VisibleForTesting
    internal data class Received(val html: String?, val bytes: Int, val files: Int, val failure: Finding?)

    private var html: String? = null
    private lateinit var card: View
    private lateinit var text: TextView
    private lateinit var appIcon: ImageView
    private lateinit var appName: TextView
    private lateinit var shield: ImageView
    private lateinit var status: TextView
    private lateinit var findings: TextView
    private lateinit var open: Button
    private lateinit var dismiss: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drawBehindSystemBars()
        setContentView(R.layout.activity_received_app)
        val root: View = findViewById(R.id.received_root)
        root.setWash(Wash.Cool)
        applySystemBarPadding(root)
        card = findViewById(R.id.received_card)
        text = findViewById(R.id.received_text)
        appIcon = findViewById(R.id.received_app_icon)
        appName = findViewById(R.id.received_app_name)
        shield = findViewById(R.id.received_shield)
        status = findViewById(R.id.received_status)
        findings = findViewById(R.id.received_findings)
        open = findViewById(R.id.received_open)
        dismiss = findViewById(R.id.received_dismiss)
        card.background = cardBackground(this)
        card.visibility = View.INVISIBLE
        val glyphSize = resources.getDimensionPixelSize(R.dimen.icon_notification)
        findViewById<ImageView>(R.id.received_glyph).setImageDrawable(
            AppIcons.load(this, packageName, glyphSize)
                ?: AppIcons.letterTile(this, getString(R.string.app_name), ContextCompat.getColor(this, R.color.ink), glyphSize),
        )
        dismiss.setOnClickListener { finish() }
        open.setOnClickListener { keep() }

        // Read at once: the read permission the sender granted ends with this activity.
        val resolver = contentResolver
        val source = intent
        lifecycleScope.launch {
            val received = withContext(Dispatchers.IO) { read(this@ReceivedAppActivity, resolver, source) }
            html = received.html
            val problems = received.failure?.let { listOf(it) }
                ?: ReceivedAppChecks.check(received.html.orEmpty(), received.bytes, received.files)
            show(received.html, problems)
        }
    }

    private fun show(html: String?, problems: List<Finding>) {
        val name = html?.let { ReceivedAppChecks.name(it) } ?: getString(R.string.received_unnamed)
        appName.text = name
        val accent = AppIcons.parseAccent(this, html?.let { BuiltAppStyle.accentOf(it) })
        appIcon.setImageDrawable(AppIcons.letterTile(this, name, accent, resources.getDimensionPixelSize(R.dimen.icon_tile_large)))
        if (problems.isEmpty()) {
            text.setText(R.string.received_text)
            shield.visibility = View.VISIBLE
            status.setText(R.string.received_checked)
            findings.visibility = View.GONE
            open.visibility = View.VISIBLE
            open.isEnabled = true
            dismiss.setText(R.string.ui_not_now)
        } else {
            text.setText(R.string.received_text_failed)
            shield.visibility = View.GONE
            status.setText(R.string.received_not_opened)
            status.setTextColor(ContextCompat.getColor(this, R.color.ink_2))
            findings.text = problems.take(2).joinToString("\n") { getString(it.text) }
            findings.visibility = View.VISIBLE
            open.visibility = View.GONE
            dismiss.setText(R.string.received_close)
        }
        dismiss.isEnabled = true
        card.visibility = View.VISIBLE
    }

    /** Open: our style block, the load test that new built apps get, then a new app under a free name. */
    private fun keep() {
        val received = html ?: return
        open.isEnabled = false
        dismiss.isEnabled = false
        lifecycleScope.launch {
            val styled = BuiltAppStyle.apply(received)
            if (testerFor(this@ReceivedAppActivity)(styled).isNotEmpty()) {
                show(received, listOf(Finding.LOAD_FAILED))
                return@launch
            }
            val id = try {
                val store = MiniAppStore(this@ReceivedAppActivity, databaseFor(this@ReceivedAppActivity))
                val name = ReceivedAppChecks.name(received) ?: getString(R.string.received_unnamed)
                // ponytail: list-then-save is not atomic; two received files with one name at the same instant could share it.
                val unique = ReceivedAppChecks.uniqueName(name, store.list().map { it.name })
                store.saveNewVersion(null, unique, styled, received = true).first
            } catch (_: Exception) {
                show(received, listOf(Finding.SAVE_FAILED))
                return@launch
            }
            startActivity(MiniAppActivity.intent(this@ReceivedAppActivity, id))
            finish()
        }
    }

    companion object {
        private const val CHUNK = 8 * 1024

        @VisibleForTesting
        internal var databaseFor: (Context) -> AppDatabase = { AppDatabase.getInstance(it) }

        /** The load test of [Tester]; replaced in unit tests, where no WebView renders. */
        @VisibleForTesting
        internal var testerFor: (Context) -> suspend (String) -> List<String> = { context -> { html -> Tester(context).test(html, allowFetch = false) } }

        /** Reads the one file of [intent]: at most [ReceivedAppChecks.MAX_BYTES] plus one byte, decoded as strict UTF-8. */
        @VisibleForTesting
        internal fun read(context: Context, resolver: ContentResolver, intent: Intent): Received {
            val uri = when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                else -> intent.data
            }
            val files = maxOf(1, intent.clipData?.itemCount ?: 1)
            if (uri == null || isOurOwn(context, uri)) return Received(null, 0, files, Finding.UNREADABLE)
            val bytes = try {
                resolver.openInputStream(uri)?.use { readLimited(it, ReceivedAppChecks.MAX_BYTES) }
                    ?: return Received(null, 0, files, Finding.UNREADABLE)
            } catch (_: Exception) {
                return Received(null, 0, files, Finding.UNREADABLE)
            }
            if (bytes.size > ReceivedAppChecks.MAX_BYTES) return Received(null, bytes.size, files, Finding.FILE_TOO_LARGE)
            val html = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
            } catch (_: CharacterCodingException) {
                return Received(null, bytes.size, files, Finding.NOT_TEXT)
            }
            return Received(html, bytes.size, files, null)
        }

        /** Up to [limit] + 1 bytes of [input]; a result longer than [limit] means the rest was not read. */
        @VisibleForTesting
        internal fun readLimited(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(CHUNK)
            while (out.size() <= limit) {
                val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * True for our own files and providers. Opening them through our own resolver would skip the checks
         * that protect them from other apps, so a sender could make us show our private data as an app.
         */
        private fun isOurOwn(context: Context, uri: Uri): Boolean = when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> uri.authority.isNullOrEmpty() ||
                context.packageManager.resolveContentProvider(uri.authority!!, 0)?.packageName == context.packageName
            ContentResolver.SCHEME_FILE -> {
                val path = uri.path?.let { runCatching { File(it).canonicalPath }.getOrNull() }
                val data = runCatching { File(context.applicationInfo.dataDir).canonicalPath }.getOrNull()
                path == null || data == null || path == data || path.startsWith("$data/")
            }
            else -> true
        }

        /** `surface`, radius 24 dp, a 3 dp pink-to-violet band along the top edge (as the thread's shared-app card). */
        private fun cardBackground(context: Context): Drawable {
            val radius = context.resources.getDimension(R.dimen.radius_shared)
            val pink = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.accent_default), 0x40)
            val violet = ContextCompat.getColor(context, R.color.wash_cool_violet)
            val edge = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(pink, violet)).apply { cornerRadius = radius }
            val surface = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.surface))
                cornerRadius = radius
            }
            val band = (3 * context.resources.displayMetrics.density).toInt()
            return LayerDrawable(arrayOf(edge, surface)).apply { setLayerInset(1, 0, band, 0, 0) }
        }
    }
}
