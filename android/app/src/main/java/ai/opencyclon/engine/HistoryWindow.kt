package ai.opencyclon.engine

/**
 * One model call of a run: the user message sent (screen, request, notes) and the model's answer.
 * [ownerWords] are the owner's answers and additions (including an approval edit) that arrived with this
 * turn; they are never dropped from the history.
 */
class Turn(val step: Int, val user: Map<String, Any>, val ownerWords: List<String> = emptyList()) {
    var assistant: Map<String, Any>? = null
    /** One line: the action the model chose. */
    var action: String = ""
    /** One line: what came of it. */
    var result: String = ""
}

/** Builds the message list sent to the model from the turns of a run. Pure. */
object HistoryWindow {
    const val FULL_TURNS = 8
    const val EARLIER_HEADER = "### Earlier steps (summary) ###\n"
    private const val MAX_FIELD = 80

    /**
     * The system message, then the last [fullTurns] turns in full. Older turns are cut to one line each
     * (action and result, plus the owner's words) and put in front of the text of the first full turn, so
     * user and assistant messages still alternate. Only the newest user message keeps its screenshot.
     */
    fun build(system: Map<String, Any>, turns: List<Turn>, fullTurns: Int = FULL_TURNS): List<Map<String, Any>> {
        val older = turns.dropLast(fullTurns)
        val recent = turns.takeLast(fullTurns)
        val out = mutableListOf(system)
        val summary = if (older.isEmpty()) null else buildString {
            append(EARLIER_HEADER)
            for (t in older) {
                for (w in t.ownerWords) append("Step ${t.step}: ").append(w).append('\n')
                append("Step ${t.step}: ").append(t.action.ifBlank { "no action" })
                if (t.result.isNotBlank()) append(" -> ").append(t.result)
                append('\n')
            }
            append('\n')
        }
        recent.forEachIndexed { i, t ->
            var user = if (i == recent.lastIndex) t.user else withoutImages(t.user)
            if (i == 0 && summary != null) user = withPrefix(user, summary)
            out.add(user)
            t.assistant?.let { out.add(it) }
        }
        return out
    }

    /** One line for the model's chosen action, e.g. `tap index=3`, `tap x=500 y=300` or `type_text text="hello"`. */
    fun describe(result: Map<String, Any?>): String {
        val type = result["action_type"] as? String ?: "unknown"
        val parts = mutableListOf(type)
        for (key in listOf("index", "x", "y", "app_name", "uri", "call", "keyword", "direction", "key", "server", "tool", "text", "question", "reason", "note")) {
            val v = result[key] ?: continue
            val s = v.toString().replace('\n', ' ').trim()
            if (s.isEmpty()) continue
            parts.add(if (v is String) "$key=\"${s.take(MAX_FIELD)}\"" else "$key=$s")
        }
        return parts.joinToString(" ")
    }

    /** A result detail cut to one short line. */
    fun oneLine(text: String): String = text.replace('\n', ' ').trim().take(MAX_FIELD * 2)

    private fun withoutImages(msg: Map<String, Any>): Map<String, Any> {
        val content = msg["content"] as? List<*> ?: return msg
        val kept = content.filter { (it as? Map<*, *>)?.get("type") != "image_url" }
        val single = kept.singleOrNull() as? Map<*, *>
        val newContent: Any = if (single != null && single["type"] == "text") single["text"] as? String ?: "" else kept
        return msg + ("content" to newContent)
    }

    private fun withPrefix(msg: Map<String, Any>, prefix: String): Map<String, Any> {
        return when (val content = msg["content"]) {
            is String -> msg + ("content" to prefix + content)
            is List<*> -> {
                var done = false
                val parts = content.map { part ->
                    val m = part as? Map<*, *>
                    if (!done && m != null && m["type"] == "text") {
                        done = true
                        m.toMutableMap().apply { this["text"] = prefix + (m["text"] as? String ?: "") }
                    } else part
                }
                msg + ("content" to (if (done) parts else parts + mapOf("type" to "text", "text" to prefix)))
            }
            else -> msg + ("content" to prefix)
        }
    }
}
