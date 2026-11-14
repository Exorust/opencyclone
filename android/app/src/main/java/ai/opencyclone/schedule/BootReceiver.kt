package ai.opencyclone.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.opencyclone.gate.ApprovalCard
import ai.opencyclone.tasks.ProactiveRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After a reboot: the same recovery as at process start. Only tasks that held the slot (running, awaiting approval,
 * paused) end; queued requests and proposals stay and start later. The service is not started from here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "Boot completed, closing tasks a stopped process left open")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ProactiveRuntime.init(context).coordinator.recoverAfterProcessStart()
                    .forEach { ApprovalCard.cancel(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}
