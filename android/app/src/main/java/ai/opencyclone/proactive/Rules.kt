package ai.opencyclone.proactive

sealed interface ParsedRule
data class HideApp(val packageName: String, val text: String) : ParsedRule
data class StopHidingApp(val packageName: String) : ParsedRule
data class UnknownApp(val name: String) : ParsedRule
data class TextRule(val text: String) : ParsedRule

class Rules(private val dao: RuleDao, private val cancelNotification: (String) -> Unit) {
    companion object {
        fun parseRule(sentence: String, installedApps: Map<String, String>): ParsedRule {
            val match = Regex("^(hide|stop hiding) (.+) notifications$", RegexOption.IGNORE_CASE)
                .matchEntire(sentence.trim()) ?: return TextRule(sentence)
            val name = match.groupValues[2].trim()
            val packageName = installedApps.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                ?: return UnknownApp(name)
            return if (match.groupValues[1].equals("hide", ignoreCase = true)) HideApp(packageName, sentence)
            else StopHidingApp(packageName)
        }
    }

    suspend fun apply(rule: ParsedRule, at: Long) {
        when (rule) {
            is HideApp -> {
                dao.removePackage(rule.packageName)
                dao.insert(RuleEntity(kind = "package", packageName = rule.packageName, text = rule.text, createdAt = at))
            }
            is StopHidingApp -> dao.removePackage(rule.packageName)
            is TextRule -> dao.insert(RuleEntity(kind = "text", text = rule.text, createdAt = at))
            is UnknownApp -> Unit
        }
    }

    suspend fun hide(incoming: Incoming): RuleEntity? {
        val rule = dao.all().firstOrNull { it.kind == "package" && it.packageName == incoming.packageName }
        if (rule != null) cancelNotification(incoming.key)
        return rule
    }

    suspend fun textRules(): List<String> = dao.all().filter { it.kind == "text" }.map { it.text }
}

fun parseRule(sentence: String, installedApps: Map<String, String>): ParsedRule = Rules.parseRule(sentence, installedApps)
