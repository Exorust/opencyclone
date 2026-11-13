package ai.opencyclon.tasks

/**
 * Our own secondary screens that "open <phrase>" reaches (plan decision 9). The request router opens them and
 * the type input's "open <app>" rule never starts an installed app with one of these names.
 */
enum class OwnScreen(val phrase: String, val title: String) {
    SETTINGS("settings", "Settings"),
    HISTORY("history", "History"),
    RULES("rules", "Rules"),
    PRIVACY("privacy", "Privacy"),
    INTEGRATIONS("integrations", "Integrations"),
    SET_UP("set up", "Set up");

    companion object {
        private val OPEN = Regex("^open\\s+(.+)$", RegexOption.IGNORE_CASE)

        /** The screen that a cleaned sentence ("open settings", any case) names, or null. */
        fun parse(text: String): OwnScreen? {
            val name = OPEN.matchEntire(text.trim())?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
                ?: return null
            return entries.firstOrNull { it.phrase.equals(name, ignoreCase = true) }
        }

        /** Names reserved for these screens. */
        val phrases: Set<String> = entries.map { it.phrase }.toSet()
    }
}
