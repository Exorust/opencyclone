package ai.opencyclone.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ai.opencyclone.R
import ai.opencyclone.ui.voice.ListeningActivity

/** Quick-settings tile "Ask": one touch opens the listening screen (F1). */
class QuickAskTileService : TileService() {

    companion object {
        /** Extra read by ControlPanelActivity to start voice input on open. */
        const val EXTRA_START_VOICE = "start_voice_input"

        /** What a tap on the tile opens: the listening screen. */
        fun tileIntent(context: Context): Intent = ListeningActivity.listenIntent(context)

        /** The old entry into the chat screen with voice; the tile no longer uses it (kept for AppEntries). */
        fun askIntent(context: Context): Intent =
            Intent(context, ControlPanelActivity::class.java)
                .putExtra(EXTRA_START_VOICE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.label = getString(R.string.h_quick_ask_tile_label)
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openAsk() } else openAsk()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAsk() {
        val intent = tileIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
