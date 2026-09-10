package ru.evrasia.research

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CookieTraceSupport {
    fun parseCookieHeader(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq).trim()] = part.substring(eq + 1)
        }
        return out
    }

    fun currentOrigin(history: List<JSONObject>, currentValue: String?): JSONObject? {
        if (currentValue != null) {
            history.asReversed().firstOrNull {
                it.optString("action") != "DELETE" &&
                    it.optString("value") == currentValue &&
                    it.optString("confidence") != "UNKNOWN"
            }?.let { return it }
        }
        return history.asReversed().firstOrNull { it.optString("action") != "DELETE" }
    }

    fun birthEvent(history: List<JSONObject>): JSONObject? {
        history.firstOrNull {
            it.optString("action") in setOf("CREATE", "SET") &&
                it.optString("confidence") == "EXACT"
        }?.let { return it }
        history.firstOrNull {
            it.optString("action") in setOf("CREATE", "SET", "OBSERVED")
        }?.let { return it }
        return history.firstOrNull()
    }

    fun regenerationEvent(history: List<JSONObject>, currentValue: String?): JSONObject? {
        val current = currentOrigin(history, currentValue)
        if (current != null && current.optString("origin") in setOf("HTTP_RESPONSE", "LIKELY_HTTP_RESPONSE", "JAVASCRIPT")) {
            return current
        }
        return birthEvent(history)
    }

    fun sourceShort(event: JSONObject?): String {
        if (event == null) return "источник неизвестен"
        val source = when (event.optString("origin", "")) {
            "HTTP_RESPONSE" -> "HTTP Set-Cookie"
            "JAVASCRIPT" -> event.optString("mechanism", "JavaScript")
            "LIKELY_HTTP_RESPONSE" -> "вероятно HTTP"
            else -> event.optString("mechanism", "неизвестно")
        }
        return "${source} · ${confidenceRu(event.optString("confidence", "UNKNOWN"))}"
    }

    fun confidenceRu(value: String): String = when (value) {
        "EXACT" -> "точно"
        "MEDIUM" -> "вероятно"
        "LOW" -> "предположение"
        else -> "неизвестно"
    }

    fun formatOrigin(event: JSONObject): String = buildString {
        val time = event.optLong("time", 0L)
        if (time > 0L) append("Время: ").append(timeText(time)).append('\n')
        append("Механизм: ").append(event.optString("mechanism", "—")).append('\n')
        append("Точность: ").append(confidenceRu(event.optString("confidence", "UNKNOWN"))).append('\n')
        val url = event.optString("url", "")
        if (url.isNotBlank()) {
            append("Запрос: ").append(event.optString("method", "GET"))
            val status = event.optInt("status", 0)
            if (status > 0) append("  ").append(status)
            append('\n').append(url).append('\n')
        } else {
            event.optString("page", "").takeIf { it.isNotBlank() }?.let {
                append("Страница: ").append(it).append('\n')
            }
        }
        event.optString("raw", "").takeIf { it.isNotBlank() }?.let {
            append("Set/Raw: ").append(it).append('\n')
        }
        event.optString("stack", "").takeIf { it.isNotBlank() }?.let {
            append("JS stack:\n").append(it.take(3000)).append('\n')
        }
        if (event.has("deltaMs")) {
            append("Cookie изменилась через ").append(event.optLong("deltaMs")).append(" мс после этого запроса\n")
        }
    }.trimEnd()

    fun requestRecipe(event: JSONObject): String = buildString {
        append(event.optString("method", "GET")).append(' ').append(event.optString("url", "—"))
        val headers = event.optJSONObject("requestHeaders")
        if (headers != null && headers.length() > 0) {
            append("\n\nHEADERS\n")
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                append(key).append(": ").append(headers.opt(key)).append('\n')
            }
        }
        val body = event.optString("requestBody", "")
        if (body.isNotBlank()) append("\nBODY\n").append(body)
    }.trimEnd()

    fun formatHistory(event: JSONObject): String = buildString {
        append(timeText(event.optLong("time", 0L))).append("  ").append(event.optString("action", "EVENT")).append('\n')
        append(sourceShort(event)).append('\n')
        val url = event.optString("url", "")
        if (url.isNotBlank()) append(event.optString("method", "GET")).append(' ').append(url).append('\n')
        if (event.has("deltaMs")) append("Δ ").append(event.optLong("deltaMs")).append(" ms\n")
        val raw = event.optString("raw", "")
        if (raw.isNotBlank()) append(raw)
    }.trimEnd()

    fun jsSetter(event: JSONObject): String {
        val raw = event.optString("raw", "")
        return if (event.optString("mechanism", "").startsWith("CookieStore")) {
            "// исходная запись была через ${event.optString("mechanism")}\n// raw: $raw"
        } else {
            "document.cookie = ${jsQuote(raw)};"
        }
    }

    fun headersMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.opt(key)?.toString().orEmpty()
        }
        return out
    }

    fun curlFor(event: JSONObject): String = buildString {
        val method = event.optString("method", "GET")
        val url = event.optString("url", "")
        append("curl -X ").append(shellQuote(method)).append(' ').append(shellQuote(url))
        headersMap(event.optJSONObject("requestHeaders")).forEach { (name, value) ->
            append(" \\\n  -H ").append(shellQuote("$name: $value"))
        }
        val body = event.optString("requestBody", "")
        if (body.isNotBlank()) append(" \\\n  --data-raw ").append(shellQuote(body))
    }

    fun timeText(ms: Long): String =
        if (ms > 0L) SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms)) else "—"

    private fun jsQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
