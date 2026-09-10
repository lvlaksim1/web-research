package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object NetworkDebuggerProjection {
    data class Result(
        val rows: List<JSONObject>,
        val changedIds: Set<Long>,
        val counterText: String
    )

    fun build(
        allItems: List<JSONObject>,
        mergeMode: Boolean,
        domain: String,
        type: String,
        method: String,
        query: String,
        methodFilters: List<String>
    ): Result {
        val base = if (mergeMode) NetworkDisplayMerger.merge(allItems) else allItems.toList()
        val changed = computeChanged(base)
        val sessions = collapseRealtimeSessions(base)
        val filtered = sessions.filter { event ->
            val action = NetworkEventClassifier.isActionEvent(event)
            val domainOk = domain == "Все домены" || NetworkEventClassifier.hostOf(NetworkEventClassifier.eventLocation(event)) == domain
            val typeOk = type == "ALL" || (action && type == "OTHER") || (!action && NetworkEventClassifier.responseKind(event) == type)
            val methodValue = NetworkEventClassifier.methodOf(event)
            val methodOk = method == "ALL" || methodValue == method || (method == "OTHER" && methodValue !in methodFilters)
            val searchOk = query.isBlank() || event.toString().contains(query, true)
            domainOk && typeOk && methodOk && searchOk
        }

        val requests = sessions.count { NetworkEventClassifier.isRequestEvent(it) }
        val actions = sessions.count { NetworkEventClassifier.isActionEvent(it) }
        val errors = sessions.count { it.has("error") || it.optInt("status", 0) >= 400 }
        val prefix = if (mergeMode) "${allItems.size} событий → ${base.size} строк" else "${allItems.size} событий"
        val filteredFlag = domain != "Все домены" || type != "ALL" || method != "ALL" || query.isNotBlank()
        val counter = "$prefix · $requests запросов · $actions действий · $errors ошибок${if (filteredFlag) " · показано ${filtered.size}" else ""}"

        return Result(filtered, changed, counter)
    }

    fun identity(event: JSONObject): Long =
        event.optLong("_storeId", event.optLong("time", 0L) xor event.toString().hashCode().toLong())

    private fun computeChanged(events: List<JSONObject>): Set<Long> {
        val changed = hashSetOf<Long>()
        val previous = HashMap<String, String>()
        events.asReversed().forEach { event ->
            if (!NetworkEventClassifier.isPlainRequestEvent(event)) return@forEach
            val fingerprint = responseFingerprint(event)
            if (fingerprint.isBlank()) return@forEach
            val key = "${NetworkEventClassifier.methodOf(event)}\n${event.optString("url", "")}"
            val old = previous[key]
            if (old != null && old != fingerprint) changed.add(identity(event))
            previous[key] = fingerprint
        }
        return changed
    }

    private fun responseFingerprint(event: JSONObject): String {
        val body = NetworkEventClassifier.responseBodyText(event)
        val headers = event.optJSONObject("responseHeaders")
        val etag = NetworkDebuggerText.headerValue(headers, "ETag")
        val lastModified = NetworkDebuggerText.headerValue(headers, "Last-Modified")
        val hasEvidence = body.isNotBlank() || event.has("status") || event.has("responseSize") ||
            event.has("decodedBodySize") || etag.isNotBlank() || lastModified.isNotBlank()
        if (!hasEvidence) return ""
        return buildString {
            append(event.optInt("status", 0)).append('|')
            append(NetworkEventClassifier.responseKind(event)).append('|')
            append(event.optLong("responseSize", event.optLong("decodedBodySize", -1L))).append('|')
            append(etag).append('|').append(lastModified).append('|')
            if (body.isNotBlank()) append(body.hashCode())
        }
    }

    private fun collapseRealtimeSessions(source: List<JSONObject>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val current = HashMap<String, JSONObject>()
        source.asReversed().forEach { event ->
            if (!isRealtimeEvent(event)) {
                out.add(event)
                return@forEach
            }
            val protocol = if (event.optString("source", "").startsWith("websocket")) "WS" else "SSE"
            val url = event.optString("url", "")
            val key = "$protocol\n$url"
            val sourceName = event.optString("source", "")
            var session = current[key]
            if (session == null || sourceName.endsWith("-open")) {
                session = JSONObject()
                    .put("source", "realtime-session")
                    .put("_realtimeSession", true)
                    .put("_realtimeProtocol", protocol)
                    .put("url", url)
                    .put("method", protocol)
                    .put("time", event.optLong("time", 0L))
                    .put("_sessionEvents", JSONArray())
                current[key] = session
                out.add(session)
            }
            session.getJSONArray("_sessionEvents").put(JSONObject(event.toString()))
            session.put("time", maxOf(session.optLong("time", 0L), event.optLong("time", 0L)))
            session.put("_sessionCount", session.getJSONArray("_sessionEvents").length())
            val state = event.optString("state", "").lowercase(Locale.US)
            if (state in setOf("closed", "close", "error")) current.remove(key)
        }
        return out.sortedByDescending { it.optLong("time", 0L) }
    }

    private fun isRealtimeEvent(event: JSONObject): Boolean {
        val source = event.optString("source", "")
        return source.startsWith("websocket-") || source.startsWith("sse-")
    }
}
