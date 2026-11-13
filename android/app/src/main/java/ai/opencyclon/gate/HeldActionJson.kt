package ai.opencyclon.gate

import org.json.JSONArray
import org.json.JSONObject

/** JSON for the `held_action` and `held_facts` columns of the tasks table. */
object HeldActionJson {
    fun toJson(held: HeldAction): String = JSONObject().apply {
        put("action_type", held.action.typeName)
        put("action", held.action.toString())
        put("reason", held.reason.name)
        put("label", held.label ?: JSONObject.NULL)
        put("bounds", held.bounds?.let { bounds -> JSONArray().apply { bounds.forEach { put(it) } } } ?: JSONObject.NULL)
        put("tap_x", held.tapX ?: JSONObject.NULL)
        put("tap_y", held.tapY ?: JSONObject.NULL)
        put("facts", factsObject(held.facts))
    }.toString()

    fun factsToJson(facts: ScreenFacts): String = factsObject(facts).toString()

    fun factsFromJson(json: String): ScreenFacts {
        val obj = JSONObject(json)
        return ScreenFacts(
            topPackage = obj.optStringOrNull("top_package"),
            title = obj.optStringOrNull("title"),
            attachmentCount = if (obj.isNull("attachment_count")) null else obj.getInt("attachment_count"),
            // Rows written before these keys existed have neither; they read as null.
            composeText = obj.optStringOrNull("compose_text"),
            attachmentNoun = obj.optStringOrNull("attachment_noun"),
        )
    }

    private fun factsObject(facts: ScreenFacts) = JSONObject().apply {
        put("top_package", facts.topPackage ?: JSONObject.NULL)
        put("title", facts.title ?: JSONObject.NULL)
        put("attachment_count", facts.attachmentCount ?: JSONObject.NULL)
        put("compose_text", facts.composeText ?: JSONObject.NULL)
        put("attachment_noun", facts.attachmentNoun ?: JSONObject.NULL)
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
}
