package se.iteca.eneotaltilltext.network

import org.json.JSONArray
import org.json.JSONObject

internal object EneoResponseParsers {
    fun isCompletedStatus(status: String) =
        status in listOf("complete", "completed", "success", "succeeded", "done", "finished")

    fun isFailedStatus(status: String) =
        status in listOf("failed", "failure", "error", "cancelled", "canceled")

    fun makeUrl(baseUrl: String, path: String, query: Map<String, String>? = null): String {
        val base = baseUrl.trim().trimEnd('/')
        val appended = path.trim().trim('/')
        val slash = if (path.endsWith("/")) "/" else ""
        var url = "$base/$appended$slash"
        if (!query.isNullOrEmpty()) {
            val queryString = query.entries.joinToString("&") { (key, value) ->
                "${encodeUrlComponent(key)}=${encodeUrlComponent(value)}"
            }
            url = "$url?$queryString"
        }
        return url
    }

    fun parseSpaces(body: String): List<EneoSpace> {
        val json = parseJson(body) ?: return emptyList()
        val rawItems = when (json) {
            is JSONObject -> {
                if (json.has("id")) {
                    listOf(json)
                } else {
                    itemsFrom(json)
                }
            }
            is JSONArray -> json.toList()
            else -> emptyList()
        }
        return rawItems.mapNotNull { item ->
            val dictionary = item as? JSONObject ?: return@mapNotNull null
            val id = dictionary.optStringOrNull("id") ?: return@mapNotNull null
            val name = dictionary.optStringOrNull("name")
                ?: dictionary.optStringOrNull("title")
                ?: dictionary.optStringOrNull("display_name")
                ?: id
            EneoSpace(id, name)
        }
    }

    fun parseApps(body: String, space: EneoSpace): List<EneoApp> {
        val json = parseJson(body) ?: return emptyList()
        val rawItems = when (json) {
            is JSONObject -> {
                val apps = json.optJSONObject("apps")
                if (apps != null) {
                    apps.optJSONArray("items")?.toList().orEmpty()
                } else {
                    itemsFrom(json)
                }
            }
            is JSONArray -> json.toList()
            else -> emptyList()
        }
        return rawItems.mapNotNull { item ->
            val dictionary = item as? JSONObject ?: return@mapNotNull null
            val id = dictionary.optStringOrNull("id") ?: return@mapNotNull null
            val name = dictionary.optStringOrNull("name")
                ?: dictionary.optStringOrNull("title")
                ?: dictionary.optStringOrNull("display_name")
                ?: id
            EneoApp(id, name, space.id, space.name)
        }
    }

    fun acceptsAudioInput(body: String): Boolean {
        val json = body.toJsonObject() ?: return false
        val fields = json.optJSONArray("input_fields") ?: return false
        for (index in 0 until fields.length()) {
            val field = fields.optJSONObject(index) ?: continue
            val type = field.optString("type")
            if (type == "audio-upload" || type == "audio-recorder") return true
        }
        return false
    }

    fun parseRunResult(body: String): RunResult {
        val json = body.toJsonObject() ?: return RunResult(null, null, null, null, null)
        val output = when {
            json.isNull("output") -> null
            json.opt("output") is String -> json.optString("output")
            json.has("output") -> formattedJsonValue(json.opt("output"))
            else -> null
        }
        val failure = firstStringValue(
            json,
            listOf("error", "message", "detail", "failure_reason", "failureReason", "failed_reason", "failedReason"),
        )
        return RunResult(
            id = json.optStringOrNull("id"),
            status = json.optStringOrNull("status"),
            createdAt = json.optStringOrNull("created_at") ?: json.optStringOrNull("createdAt"),
            output = output,
            failureMessage = failure ?: output,
        )
    }

    private fun encodeUrlComponent(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun itemsFrom(json: Any): List<Any> = when (json) {
        is JSONArray -> json.toList()
        is JSONObject -> json.optJSONArray("items")?.toList()
            ?: json.optJSONArray("data")?.toList()
            ?: json.optJSONArray("results")?.toList()
            ?: emptyList()
        else -> emptyList()
    }

    private fun parseJson(body: String): Any? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private fun String.toJsonObject(): JSONObject? = try {
        JSONObject(this)
    } catch (_: Exception) {
        null
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        val text = (value as? String) ?: value?.toString()
        return text?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONArray.toList(): List<Any> = (0 until length()).map { get(it) }

    private fun firstStringValue(json: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            json.optStringOrNull(key)?.let { return it }
        }
        return null
    }

    private fun formattedJsonValue(value: Any?): String {
        return when (value) {
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> value?.toString().orEmpty()
        }
    }
}
