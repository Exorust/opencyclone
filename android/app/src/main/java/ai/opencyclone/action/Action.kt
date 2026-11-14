package ai.opencyclone.action

import org.json.JSONObject

/**
 * Sealed class representing all possible phone actions.
 * Mirrors the Python ActionType enum from actions.py.
 */
sealed class Action(val typeName: String, open val thought: String) {

    data class CallTool(
        val server: String,
        val tool: String,
        val argumentsJson: String,
        override val thought: String = ""
    ) : Action("call_tool", thought)

    /**
     * [index]: the element number the model named (F18.1), null for a tap by position. After the engine resolved the
     * number, [x]/[y] are the element's present centre (0-1000), [label] its label and [targetPackage] its window's app.
     */
    data class Tap(
        val x: Int,
        val y: Int,
        override val thought: String = "",
        val index: Int? = null,
        val label: String? = null,
        val targetPackage: String? = null,
        val targetKey: String? = null,
    ) : Action("tap", thought)

    data class LongPress(
        val x: Int,
        val y: Int,
        override val thought: String = "",
        val index: Int? = null,
        val label: String? = null,
        val targetPackage: String? = null,
        val targetKey: String? = null,
    ) : Action("long_press", thought)

    /**
     * Opens [uri] with ACTION_VIEW on the hidden display (F18.4); with [call] a `tel:` link is dialled with ACTION_CALL.
     * [targetPackage] and [targetLabel]: the app that opens it, filled in by the engine before the gate looks at it.
     */
    data class OpenLink(
        val uri: String,
        val call: Boolean = false,
        override val thought: String = "",
        val targetPackage: String? = null,
        val targetLabel: String? = null,
    ) : Action("open_link", thought) {
        val scheme: String get() = uri.substringBefore(':', "").trim().lowercase(java.util.Locale.ROOT)
        /** The number of a `tel:` link, without the scheme. */
        val number: String get() = percentDecode(uri.substringAfter(':').substringBefore('?')).trim()

        private fun percentDecode(s: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val hex = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) { out.write(hex); i += 3 } else { out.write(c.toString().toByteArray()); i++ }
            }
            return out.toString("UTF-8")
        }
    }

    data class OpenApp(
        val appName: String,
        override val thought: String = ""
    ) : Action("open_app", thought)

    data class Swipe(
        val direction: String,
        override val thought: String = ""
    ) : Action("swipe", thought)

    data class KeyEvent(
        val key: String,
        override val thought: String = ""
    ) : Action("key_event", thought)

    data class TypeText(
        val text: String,
        override val thought: String = ""
    ) : Action("type_text", thought)

    data class Wait(
        override val thought: String = ""
    ) : Action("wait", thought)

    data class Finish(
        override val thought: String = ""
    ) : Action("finish", thought)

    data class Fail(
        val reason: String,
        override val thought: String = ""
    ) : Action("fail", thought)

    data class AskUser(
        val question: String,
        override val thought: String = ""
    ) : Action("ask_user", thought)

    data class HandoffUser(
        val reason: String,
        override val thought: String = ""
    ) : Action("handoff_user", thought)

    data class Note(
        val note: String,
        override val thought: String = ""
    ) : Action("note", thought)

    data class Remember(
        val key: String,
        val value: String,
        val category: String = "",
        override val thought: String = ""
    ) : Action("remember", thought)

    data class ListApps(
        val keyword: String = "",
        override val thought: String = ""
    ) : Action("list_apps", thought)

    companion object {
        /**
         * Parse an Action from the LLM tool call result map.
         */
        private fun extractInt(value: Any?): Int? = when (value) {
            is Number -> value.toInt()
            is List<*> -> (value.firstOrNull() as? Number)?.toInt()
            else -> null
        }

        private fun extractCoords(map: Map<String, Any?>): Pair<Int?, Int?> {
            val xVal = map["x"]
            val yVal = map["y"]
            if (xVal is List<*> && xVal.size >= 2 && yVal == null) {
                return Pair((xVal[0] as? Number)?.toInt(), (xVal[1] as? Number)?.toInt())
            }
            return Pair(extractInt(xVal), extractInt(yVal))
        }

        fun fromMap(map: Map<String, Any?>): Action {
            val thought = (map["thought"] as? String) ?: ""
            val actionType = ((map["action_type"] as? String) ?: "fail").trim()

            return when (actionType) {
                "call_tool" -> CallTool(
                    server = map["server"] as? String
                        ?: throw IllegalArgumentException("call_tool missing server"),
                    tool = map["tool"] as? String
                        ?: throw IllegalArgumentException("call_tool missing tool"),
                    argumentsJson = when (val arguments = map["arguments"]) {
                        null -> "{}"
                        is Map<*, *> -> JSONObject(arguments).toString()
                        is JSONObject -> arguments.toString()
                        is String -> JSONObject(arguments).toString()
                        else -> throw IllegalArgumentException("call_tool arguments must be an object")
                    },
                    thought = thought
                )
                // A tap by number needs no position; the engine fills it from the element list.
                "tap" -> extractCoords(map).let { (cx, cy) ->
                    val index = extractInt(map["index"])
                    Tap(
                        x = cx ?: if (index != null) 0 else throw IllegalArgumentException("tap action missing required field 'x'"),
                        y = cy ?: if (index != null) 0 else throw IllegalArgumentException("tap action missing required field 'y'"),
                        thought = thought,
                        index = index
                    )
                }
                "long_press" -> extractCoords(map).let { (cx, cy) ->
                    val index = extractInt(map["index"])
                    LongPress(
                        x = cx ?: if (index != null) 0 else throw IllegalArgumentException("long_press action missing required field 'x'"),
                        y = cy ?: if (index != null) 0 else throw IllegalArgumentException("long_press action missing required field 'y'"),
                        thought = thought,
                        index = index
                    )
                }
                "open_link" -> OpenLink(
                    uri = ((map["uri"] as? String) ?: "").trim(),
                    call = map["call"] == true || (map["call"] as? String)?.equals("true", ignoreCase = true) == true,
                    thought = thought
                )
                "open_app" -> OpenApp(
                    appName = (map["app_name"] as? String) ?: "",
                    thought = thought
                )
                "swipe" -> Swipe(
                    direction = (map["direction"] as? String) ?: "up",
                    thought = thought
                )
                "key_event" -> KeyEvent(
                    key = (map["key"] as? String) ?: "back",
                    thought = thought
                )
                "type_text" -> TypeText(
                    text = (map["text"] as? String) ?: "",
                    thought = thought
                )
                "wait" -> Wait(thought = thought)
                "finish" -> Finish(thought = thought)
                "fail" -> Fail(
                    reason = (map["reason"] as? String) ?: "unknown reason",
                    thought = thought
                )
                "ask_user" -> AskUser(
                    question = (map["question"] as? String) ?: thought,
                    thought = thought
                )
                "handoff_user" -> HandoffUser(
                    reason = (map["handoff_reason"] as? String) ?: thought,
                    thought = thought
                )
                "note" -> Note(
                    note = (map["note"] as? String) ?: thought,
                    thought = thought
                )
                "remember" -> Remember(
                    key = (map["memory_key"] as? String) ?: "",
                    value = (map["memory_value"] as? String) ?: "",
                    category = (map["memory_category"] as? String) ?: "",
                    thought = thought
                )
                "list_apps" -> ListApps(
                    keyword = (map["keyword"] as? String) ?: "",
                    thought = thought
                )
                else -> Fail(reason = "Unknown action type: $actionType", thought = thought)
            }
        }
    }
}
