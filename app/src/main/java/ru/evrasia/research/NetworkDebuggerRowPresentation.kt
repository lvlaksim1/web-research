package ru.evrasia.research

import org.json.JSONObject

internal object NetworkDebuggerRowPresentation {
    fun flags(event: JSONObject, changedIds: Set<Long>): String = buildList {
        if (NetworkEventClassifier.isRealtimeSession(event)) return@buildList
        if (NetworkEventClassifier.eventSources(event).size > 1) {
            add(if (event.optString("_mergeConfidence") == "MEDIUM") "~MERGE" else "✓MERGE")
        }
        if (NetworkEventClassifier.hasRequestBody(event)) add("BODY")
        if (isCached(event)) add("CACHE")
        if (isRedirect(event)) add("REDIRECT")
        if (hasAuth(event)) add("AUTH")
        if (changedIds.contains(NetworkDebuggerProjection.identity(event))) add("CHANGED")
        if (event.optString("source", "") == "replay") add("REPLAY")
    }.joinToString("  ")

    fun hasAuth(event: JSONObject): Boolean {
        val headers = event.optJSONObject("requestHeaders") ?: event.optJSONObject("headers") ?: return false
        return NetworkDebuggerText.headerValue(headers, "Authorization").isNotBlank() ||
            NetworkDebuggerText.headerValue(headers, "Proxy-Authorization").isNotBlank()
    }

    fun kindColor(kind: String, palette: WebUiTheme.Palette): Int = when (kind) {
        "JSON" -> palette.accent
        "HTML" -> palette.blue
        "JS" -> palette.orange
        "CSS" -> palette.accent
        "IMG" -> palette.orange
        "PDF" -> palette.red
        "TEXT" -> palette.text
        "BIN" -> palette.secondary
        else -> palette.secondary
    }

    fun actionLabel(event: JSONObject): String {
        val action = event.optString("action", "action").uppercase()
        val target = event.optJSONObject("target")
        val name = target?.optString("text", "")?.trim()?.take(80).orEmpty().ifBlank {
            target?.optString("id", "")?.takeIf { it.isNotBlank() }
                ?: target?.optString("role", "")?.takeIf { it.isNotBlank() }
                ?: target?.optString("tag", "").orEmpty()
        }
        return if (name.isBlank()) action else "$action  \"$name\""
    }

    private fun isCached(event: JSONObject): Boolean {
        val cache = event.optString("cache", "")
        if (cache.isNotBlank() && !cache.equals("network", true)) return true
        return event.has("transferSize") &&
            event.optLong("transferSize", -1L) == 0L &&
            event.optLong("decodedBodySize", 0L) > 0L
    }

    private fun isRedirect(event: JSONObject): Boolean =
        event.optBoolean("redirected", false) ||
            event.optString("redirectURL", "").isNotBlank() ||
            event.optInt("status", 0) in 300..399
}
