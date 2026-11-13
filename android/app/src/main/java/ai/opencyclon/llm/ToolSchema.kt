package ai.opencyclon.llm

import ai.opencyclon.engine.LlmPrompts
import org.json.JSONArray
import org.json.JSONObject

object ToolSchema {

    fun phoneActionTool(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
            put("name", "phone_action")
            put("description", LlmPrompts.toolDescription())
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("thought", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("thought"))
                    })
                    // Optional: the owner-facing status line; the engine cleans it (engine/Caption.kt).
                    put("caption", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("caption"))
                    })
                    put("action_type", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("tap")
                            put("long_press")
                            put("call_tool")
                            put("open_app")
                            put("open_link")
                            put("list_apps")
                            put("swipe")
                            put("key_event")
                            put("type_text")
                            put("wait")
                            put("finish")
                            put("fail")
                            put("ask_user")
                            put("handoff_user")
                            put("note")
                            put("remember")
                        })
                        put("description", LlmPrompts.paramDescription("action_type"))
                    })
                    for (key in listOf("server", "tool")) {
                        put(key, JSONObject().put("type", "string")
                            .put("description", LlmPrompts.paramDescription(key)))
                    }
                    put("arguments", JSONObject().put("type", "object")
                        .put("description", LlmPrompts.paramDescription("arguments")))
                    put("index", JSONObject().apply {
                        put("type", "integer")
                        put("description", LlmPrompts.paramDescription("index"))
                    })
                    put("x", JSONObject().apply {
                        put("type", "integer")
                        put("description", LlmPrompts.paramDescription("x"))
                    })
                    put("y", JSONObject().apply {
                        put("type", "integer")
                        put("description", LlmPrompts.paramDescription("y"))
                    })
                    put("app_name", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("app_name"))
                    })
                    put("uri", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("uri"))
                    })
                    put("call", JSONObject().apply {
                        put("type", "boolean")
                        put("description", LlmPrompts.paramDescription("call"))
                    })
                    put("keyword", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("keyword"))
                    })
                    put("direction", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("up")
                            put("down")
                            put("left")
                            put("right")
                        })
                        put("description", LlmPrompts.paramDescription("direction"))
                    })
                    put("key", JSONObject().apply {
                        put("type", "string")
                        put("enum", JSONArray().apply {
                            put("back")
                            put("home")
                            put("enter")
                            put("recent")
                        })
                        put("description", LlmPrompts.paramDescription("key"))
                    })
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("text"))
                    })
                    put("reason", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("reason"))
                    })
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("question"))
                    })
                    put("handoff_reason", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("handoff_reason"))
                    })
                    put("note", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("note"))
                    })
                    put("memory_key", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("memory_key"))
                    })
                    put("memory_value", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("memory_value"))
                    })
                    put("memory_category", JSONObject().apply {
                        put("type", "string")
                        put("description", LlmPrompts.paramDescription("memory_category"))
                    })
                })
                put("required", JSONArray().apply {
                    put("thought")
                    put("action_type")
                })
            })
            })
        }
    }

    fun toolsArray(): JSONArray {
        return JSONArray().put(phoneActionTool())
    }

    fun anthropicPhoneActionTool(): JSONObject {
        return JSONObject().apply {
            put("name", "phone_action")
            put("description", LlmPrompts.toolDescription())
            put("input_schema", phoneActionTool().getJSONObject("function").getJSONObject("parameters"))
        }
    }

    fun anthropicToolsArray(): JSONArray {
        return JSONArray().put(anthropicPhoneActionTool())
    }
}
