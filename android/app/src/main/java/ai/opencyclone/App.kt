package ai.opencyclone

import ai.opencyclone.feed.FeedLocator
import ai.opencyclone.feed.HomeCommands
import ai.opencyclone.feed.RealFeed
import ai.opencyclone.feed.TaskHistory
import ai.opencyclone.tasks.ProactiveRuntime
import android.app.Application
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class App : Application() {

    companion object {
        @Volatile
        var agentService: AgentService? = null
    }

    override fun onCreate() {
        super.onCreate()

        // Test path: a ChatGPT sign-in pushed as files/chatgpt_auth.json is imported once, then deleted.
        ai.opencyclone.llm.PlanSignIn.importAuthFileIfPresent(this)

        // The interface reads only the feed contract. The runtime behind it is built on first use.
        val context = applicationContext
        val history by lazy { TaskHistory.create(context) }
        FeedLocator.feed = RealFeed(
            liveSource = { ProactiveRuntime.init(context).liveState },
            recorder = { ProactiveRuntime.init(context).recorder },
            previewSource = { ProactiveRuntime.init(context).previews },
            taskHistory = { history },
        )
        FeedLocator.commands = LazyCommands(context) { history }

        // Process start: close what a stopped process left open and restore the notification window.
        // ponytail: skipped under Robolectric, where every test would otherwise build the whole runtime.
        if (Build.FINGERPRINT != "robolectric") ProactiveRuntime.init(this)
    }

    private class LazyCommands(private val context: Context, private val history: () -> TaskHistory) : HomeCommands {
        private val real get() = ProactiveRuntime.init(context).commands
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        override fun submit(text: String) = real.submit(text)
        override suspend fun approve(taskId: Long): Boolean = real.approve(taskId)
        override fun reject(taskId: Long) = real.reject(taskId)
        override fun stop(taskId: Long) = real.stop(taskId)
        override fun openLive(context: Context, taskId: Long) = real.openLive(context, taskId)
        override fun deleteTask(taskId: Long) { io.launch { history().delete(taskId) } }
        override fun askAgain(taskId: Long) {
            io.launch {
                val words = history().title(taskId) ?: return@launch
                withContext(Dispatchers.Main) { real.submit(words) }
            }
        }
        override fun confirm(itemId: Long, yes: Boolean) = real.confirm(itemId, yes)
    }
}
