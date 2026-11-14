
package ai.astra.liveagent

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AgentApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun plan(baseUrl: String, instruction: String, jpeg: ByteArray): AgentAction {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://")) {
            "Use the HTTPS AppDeploy URL."
        }

        val body = JSONObject()
            .put("instruction", instruction)
            .put("screenshot", JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$normalized/api/agent/plan")
            .header("Accept", "application/json")
            .header("Origin", normalized)
            .header("Referer", "$normalized/")
            .header("User-Agent", "Astra-Live-Agent-Android")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val message = try {
                    JSONObject(responseText).optString("error")
                        .ifBlank {
                            JSONObject(responseText).optString("message")
                        }
                } catch (_: Exception) {
                    responseText.replace(Regex("\\s+"), " ").take(350)
                }

                val detail = message.ifBlank {
                    response.message.ifBlank { "No server message" }
                }

                throw IllegalStateException(
                    "Astra API HTTP ${response.code}: $detail"
                )
            }

            val root = JSONObject(responseText)
            return AgentAction.fromJson(root.getJSONObject("plan"))
        }
    }
}
