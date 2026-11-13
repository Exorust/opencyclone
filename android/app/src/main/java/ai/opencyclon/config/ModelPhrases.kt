package ai.opencyclon.config

import java.util.Locale

/** A typed or spoken sentence about the model, handled before the agent sees it. */
sealed class ModelPhrase {
    /** "use model gpt 5.6 terra": the name as said. */
    data class Use(val name: String) : ModelPhrase()
    /** "which model". */
    object Which : ModelPhrase()
}

/** Parses and answers the two model phrases. Pure functions apart from [handle], which saves the choice. */
object ModelPhrases {
    private val USE = Regex("^(?:use|switch to|change to)\\s+(?:the\\s+)?model\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val WHICH = Regex("^(?:which|what)\\s+model(?:\\s+(?:is this|are you using|do you use|is in use))?$", RegexOption.IGNORE_CASE)

    /** "use model X" or "which model", after the router's clean-up (no wake name, no end punctuation). */
    fun parse(text: String): ModelPhrase? {
        val t = text.trim().trimEnd('.', '?', '!', ' ').replace(Regex("\\s+"), " ")
        WHICH.matchEntire(t)?.let { return ModelPhrase.Which }
        USE.matchEntire(t)?.let { m -> m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { return ModelPhrase.Use(it) } }
        return null
    }

    /**
     * The model id for a spoken [name]: the [known] id that matches when case, spaces, hyphens and the word
     * "point" are ignored ("GPT 5 point 6 Terra" is "gpt-5.6-terra"); otherwise the name as typed.
     */
    fun resolve(name: String, known: List<String>): String {
        val key = normalize(name)
        return known.firstOrNull { normalize(it) == key } ?: name.trim()
    }

    /** Saves the choice for a "use model" phrase, or describes the model. Returns the sentence to say. */
    fun handle(config: ConfigRepository, phrase: ModelPhrase, known: List<String>): String = when (phrase) {
        is ModelPhrase.Use -> {
            val id = resolve(phrase.name, known)
            config.chooseModel(id)
            "The next task uses $id."
        }
        ModelPhrase.Which -> "I use ${config.model}."
    }

    private fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("\\bpoint\\b"), ".").replace(Regex("[^a-z0-9.]"), "")
}
