package ai.astra.liveagent

import org.json.JSONObject

data class AgentAction(
    val action: String,
    val explanation: String = "",
    val x: Int = 0,
    val y: Int = 0,
    val endX: Int = 0,
    val endY: Int = 0,
    val text: String = "",
    val app: String = "",
    val question: String = "",
    val requiresConfirmation: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject): AgentAction = AgentAction(
            action = json.optString("action", "ask_user"),
            explanation = json.optString("explanation"),
            x = json.optInt("x"),
            y = json.optInt("y"),
            endX = json.optInt("endX"),
            endY = json.optInt("endY"),
            text = json.optString("text"),
            app = json.optString("app"),
            question = json.optString("question"),
            requiresConfirmation = json.optBoolean("requiresConfirmation", false)
        )
    }
}
