package ai.opencyclone.tasks

import ai.opencyclone.WakeNames
import ai.opencyclone.config.ModelPhrase
import ai.opencyclone.config.ModelPhrases
import ai.opencyclone.proactive.HideApp
import ai.opencyclone.proactive.NotificationLogEntity
import ai.opencyclone.proactive.Rules
import ai.opencyclone.proactive.StopHidingApp
import ai.opencyclone.proactive.Summary
import ai.opencyclone.proactive.TextRule
import ai.opencyclone.proactive.UnknownApp
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where "What did I miss" keeps the time of the last summary. */
interface LastSummaryStore {
    fun get(): Long
    fun set(at: Long)

    class InMemory : LastSummaryStore {
        @Volatile private var at = 0L
        override fun get() = at
        override fun set(at: Long) { this.at = at }
    }

    /** Survives a restart. */
    class Prefs(private val prefs: SharedPreferences) : LastSummaryStore {
        override fun get() = prefs.getLong(KEY, 0L)
        override fun set(at: Long) { prefs.edit().putLong(KEY, at).apply() }
        companion object { const val KEY = "last_summary_at" }
    }
}

/** Sends one typed or spoken sentence to the handler that owns it. */
class RequestRouter(
    private val handlers: List<suspend (String) -> Boolean>,
    private val coordinator: TaskCoordinator,
    private val rules: Rules,
    private val summary: Summary,
    private val installedApps: () -> Map<String, String>,
    private val say: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val onWindowStarted: (endsAt: Long) -> Unit = {},
    private val onWindowStopped: () -> Unit = {},
    private val lastSummary: LastSummaryStore = LastSummaryStore.InMemory(),
    /** "Connect my <name> (service) at <address>": opens the Integrations screen filled in. */
    private val onConnectService: (name: String, address: String) -> Unit = { _, _ -> },
    /** Every request, as the owner typed or spoke it, before it is handled (the thread's owner row). */
    private val onRequest: (String) -> Unit = {},
    /** A hide rule was saved; the argument is the owner's rule sentence. */
    private val onRuleSaved: (String) -> Unit = {},
    /** "Open settings" and the like: opens our own screen; never an agent task. */
    private val onOpenScreen: (OwnScreen) -> Unit = {},
    /** Local consent controls, before any model handler. True requests confirmation. */
    private val onSuggestions: suspend (Boolean) -> Unit = {},
    /** "use model <name>" and "which model": saves or reads the model and returns the sentence to say (ModelPhrases.handle). */
    private val onModelPhrase: ((ModelPhrase) -> String)? = null,
    private val inactiveWindowSentence: String = "Stopped handling notifications.",
) {
    companion object {
        private val START_WINDOW = Regex(
            "^handle (?:my |all )?notifications for the next (?:(\\d+|an?|one)\\s+)?(hours?|minutes?|mins?)(?:\\s+please)?$",
            RegexOption.IGNORE_CASE)
        private val STOP_WINDOW = Regex("^stop handling (?:my )?notifications(?:\\s+please)?$", RegexOption.IGNORE_CASE)
        private val WHAT_DID_I_MISS = Regex("^what did i miss$", RegexOption.IGNORE_CASE)
        private val STOP_HIDING = Regex("^stop hiding .+ notifications$", RegexOption.IGNORE_CASE)
        private val CONNECT_SERVICE = Regex("^connect (?:to )?(?:my |the )?(.+?)(?: service)? at (\\S+)$", RegexOption.IGNORE_CASE)
        const val CONNECT_PROMPT = "Enter the token for %s on the Integrations screen"

        fun formatTime(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

        /** Three short lines under the sentence that ends a window: "Handled 2.", "Hidden 1.", "Needs you 0.". */
        fun windowSummaryText(summary: WindowSummary): String =
            "Handled ${summary.handled}.\nHidden ${summary.hidden}.\nNeeds you ${summary.needsYou}."

        /** Removes the wake name, "please", and end punctuation. */
        fun clean(sentence: String): String = WakeNames.strip(sentence).trimEnd('.', '!', '?', ' ')
    }

    /** [recordOwner] is false when the caller already wrote the owner's row for these words. */
    suspend fun route(sentence: String, recordOwner: Boolean = true) {
        val text = clean(sentence)
        if (text.isEmpty()) return
        if (recordOwner) onRequest(sentence.trim())
        ai.opencyclone.proactive.Suggestions.phrase(text)?.let {
            onSuggestions(it)
            return
        }
        // Every handler and the agent get the sentence without the wake name (WakeNames, one place).
        val stripped = WakeNames.strip(sentence)
        onModelPhrase?.let { answer ->
            ModelPhrases.parse(text)?.let { phrase ->
                say(answer(phrase))
                return
            }
        }
        OwnScreen.parse(text)?.let { screen ->
            onOpenScreen(screen)
            say("Opened ${screen.title}.")
            return
        }
        if (STOP_WINDOW.matches(text)) {
            val counts = coordinator.stopWindow()
            onWindowStopped()
            say(if (counts == null) inactiveWindowSentence else "Stopped handling notifications.\n${windowSummaryText(counts)}")
            return
        }
        for (handler in handlers) {
            if (handler(stripped)) return
        }
        START_WINDOW.matchEntire(text)?.let { match ->
            val amount = match.groupValues[1].toIntOrNull() ?: 1
            val minutes = if (match.groupValues[2].startsWith("h", ignoreCase = true)) amount * 60 else amount
            if (minutes <= 0) return@let
            coordinator.startWindow(minutes)
            val endsAt = now() + minutes * 60_000L
            onWindowStarted(endsAt)
            say("Handling notifications until ${formatTime(endsAt)}")
            return
        }
        CONNECT_SERVICE.matchEntire(text)?.let { match ->
            val name = match.groupValues[1].trim()
            onConnectService(name, match.groupValues[2])
            say(CONNECT_PROMPT.format(name))
            return
        }
        if (WHAT_DID_I_MISS.matches(text)) {
            say(summaryText())
            return
        }
        if (text.startsWith("hide ", ignoreCase = true) || STOP_HIDING.matches(text)) {
            val apps = installedApps()
            when (val rule = Rules.parseRule(text, apps)) {
                is UnknownApp -> say("No app named ${rule.name}")
                is HideApp, is StopHidingApp, is TextRule -> {
                    rules.apply(rule, now())
                    say("Rule saved: ${text.replaceFirstChar { it.lowercase() }}")
                    onRuleSaved(text)
                }
            }
            return
        }
        coordinator.submitUserRequest(stripped)
    }

    private suspend fun summaryText(): String {
        val at = now()
        val result = summary.since(lastSummary.get())
        lastSummary.set(at)
        val labels = installedApps().entries.associate { (label, pkg) -> pkg to label }
        fun line(entry: NotificationLogEntity) = "- ${entry.title} (${labels[entry.packageName] ?: entry.packageName})"
        return buildString {
            append("Needs a reply (${result.needsReply.size})")
            result.needsReply.forEach { append("\n").append(line(it)) }
            append("\nFor information (${result.forInformation.size})")
            result.forInformation.forEach { append("\n").append(line(it)) }
            append("\nHidden (${result.hidden.size})")
            result.hidden.groupBy { it.ruleText }.forEach { (rule, entries) ->
                append("\n- ${entries.size}: ")
                append(if (rule != null) "your rule: ${rule.replaceFirstChar { it.lowercase() }}" else "hidden")
            }
        }
    }
}
