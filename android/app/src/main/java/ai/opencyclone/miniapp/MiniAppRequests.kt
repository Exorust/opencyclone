package ai.opencyclone.miniapp

import ai.opencyclone.WakeNames

/** Recognises owner sentences about built apps: build one, share one, or change one. */
object MiniAppRequests {
    private val options = setOf(RegexOption.IGNORE_CASE)
    private val build = Regex("""^(?:build|make)\s+(?:me\s+)?an?\s+app\b.*""", options)
    private val shareNamed = Regex("""^share\s+(?:the\s+|my\s+)?(.+?)\s+app\s+with\s+(.+?)[\s.!?]*$""", options)
    private val shareIt = Regex("""^(?:(?:can|could|would)\s+you\s+)?share\s+it\s+with\s+(.+?)[\s.!?]*$""", options)
    private val changeStart = Regex("""^(?:add|make\s+the|change|remove|update|edit)\s+.*""", options)
    /** "the app", "my app", or "it": a change of the app built or changed in the last 10 minutes. */
    private val meansRecent = Regex("""\b(?:the\s+app|my\s+app|it)\b""", options)

    /** Returns true when the sentence was a built-app request and has been handled. */
    suspend fun tryHandle(
        sentence: String, builder: Builder, store: MiniAppStore,
        startAgentTask: suspend (goal: String) -> Unit, say: (String) -> Unit,
        onResult: (BuildResult) -> Unit = {}
    ): Boolean {
        // The router removed the wake name already; a direct caller gets the same treatment.
        val text = WakeNames.strip(sentence)
        if (text.isEmpty()) return false

        if (build.matches(text)) {
            onResult(builder.build(text))
            return true
        }

        shareNamed.find(text)?.let { match ->
            val apps = store.findByName(match.groupValues[1])
            return when (apps.size) {
                0 -> { say("$NO_APP_NAMED${match.groupValues[1]}"); true }
                1 -> share(apps.single(), match.groupValues[2], store, startAgentTask, say)
                else -> { say(which(apps)); true }
            }
        }

        shareIt.find(text)?.let { match ->
            val recent = store.recent()
            if (recent.size == 1) return share(recent.single(), match.groupValues[1], store, startAgentTask, say)
            if (recent.size > 1) { say(which(recent)); return true }
            return false
        }

        // A sentence that only mentions an app's name ("what's the weather today" and an app named
        // Weather) is not a change: the name must stand next to the word "app", or the sentence must
        // start with a change verb.
        val startsWithChange = changeStart.matches(text)
        val named = store.findByName(text).filter { app ->
            val name = Regex.escape(app.name)
            Regex("""\b$name\s+app\b|\bapp\s+$name\b""", options).containsMatchIn(text) ||
                (startsWithChange && Regex("""\b$name\b""", options).containsMatchIn(text))
        }
        if (named.size == 1) {
            onResult(builder.change(named.single().id, text))
            return true
        }
        if (named.size > 1) {
            say(which(named))
            return true
        }

        // A change verb alone is not enough ("Add a note titled pm-test" is for the agent): the sentence must name
        // the app (above) or say "the app", "my app" or "it" within 10 minutes of that app's build or last change.
        if (startsWithChange && meansRecent.containsMatchIn(text)) {
            val recent = store.recent()
            if (recent.size == 1) {
                onResult(builder.change(recent.single().id, text))
                return true
            }
            if (recent.size > 1) {
                say(which(recent))
                return true
            }
        }
        return false
    }

    private suspend fun share(
        app: MiniAppEntity, person: String, store: MiniAppStore,
        startAgentTask: suspend (goal: String) -> Unit, say: (String) -> Unit
    ): Boolean {
        val file = store.shareFile(app.id)
        if (file == null) {
            say(SHARE_FAILED)
            return true
        }
        startAgentTask("Send the file ${file.absolutePath} to $person as an attachment")
        return true
    }

    private fun which(apps: List<MiniAppEntity>) = WHICH_APP + apps.joinToString(", ") { it.name } + "?"

    const val WHICH_APP = "Which app do you mean: "
    const val NO_APP_NAMED = "No built app named "
    const val SHARE_FAILED = "Could not prepare the app file for sharing"
}
