package ru.evrasia.research

import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object NetworkDebuggerText {
    private val displaySourceOrder = listOf("webview", "fetch", "xhr", "resource-timing", "resource-copy", "replay", "fetch-meta", "xhr-meta")

    fun buildRequestSummary(event: JSONObject): String = buildString {
        append("Method: ").append(NetworkEventClassifier.methodOf(event)).append('\n')
        append("URL: ").append(event.optString("url", "—")).append('\n')
        appendUrlBasics(this, event.optString("url", ""))
        append("Source: ").append(displaySource(event).ifBlank { "—" }).append('\n')
        if (event.has("time")) append("Time: ").append(formatTime(event.optLong("time"))).append("  (").append(event.optLong("time")).append(")\n")
        appendField(this, event, "initiatorType", "Initiator type")
        appendField(this, event, "initiatorStack", "Initiator stack")
        appendField(this, event, "_replayOfStoreId", "Replay of store id")
    }.trimEnd()

    fun buildResponseSummary(event: JSONObject): String = buildString {
        if (event.has("status")) append("Status: ").append(event.optInt("status")).append(' ').append(event.optString("statusText", "")).append('\n')
        append("Content type: ").append(NetworkEventClassifier.responseKind(event)).append('\n')
        appendField(this, event, "finalUrl", "Final URL")
        appendField(this, event, "redirectURL", "Redirect URL")
        appendField(this, event, "redirected", "Redirected")
        appendField(this, event, "redirectCount", "Redirect count")
        appendField(this, event, "httpVersion", "Protocol")
        appendField(this, event, "cache", "Delivery/cache")
        appendField(this, event, "deliveryType", "Delivery type")
        appendField(this, event, "renderBlockingStatus", "Render blocking")
        appendField(this, event, "responseType", "Response type")
        appendField(this, event, "mimeType", "MIME type")
        if (event.has("duration")) append("Duration: ").append(formatDuration(event.optDouble("duration", 0.0))).append("  (").append(event.opt("duration")).append(" ms)\n")
        listOf(
            "responseSize" to "Response size",
            "transferSize" to "Transferred",
            "encodedBodySize" to "Encoded body",
            "decodedBodySize" to "Decoded body"
        ).forEach { (key, label) ->
            if (event.has(key)) {
                val bytes = event.optLong(key)
                append(label).append(": ").append(formatBytes(bytes)).append("  (").append(bytes).append(" bytes)\n")
            }
        }
        if (event.has("error")) append("\nERROR\n").append(event.optString("error")).append('\n')
    }.trimEnd()

    fun requestHeaderPairs(event: JSONObject): List<Pair<String, String>> {
        val headers = event.optJSONObject("requestHeaders") ?: event.optJSONObject("headers")
        return objectHeaderPairs(headers)
    }

    fun responseHeaderPairs(event: JSONObject): List<Pair<String, String>> {
        val headers = event.optJSONObject("responseHeaders")
        if (headers != null) return objectHeaderPairs(headers)
        return rawHeaderPairs(event.optString("responseHeadersRaw", ""))
    }

    fun formatHeaders(headers: List<Pair<String, String>>): String =
        if (headers.isEmpty()) "—" else headers.joinToString("\n") { (name, value) -> "$name: $value" }

    fun formatPairs(pairs: List<Pair<String, String>>): String =
        if (pairs.isEmpty()) "—" else pairs.joinToString("\n") { (name, value) -> "$name: $value" }

    fun queryPairs(raw: String): List<Pair<String, String>> {
        val query = try { URL(raw).query } catch (_: Exception) { null } ?: return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { part ->
            val p = part.indexOf('=')
            val name = if (p >= 0) part.substring(0, p) else part
            val value = if (p >= 0) part.substring(p + 1) else ""
            urlDecode(name) to urlDecode(value)
        }
    }

    fun requestFormPairs(event: JSONObject): List<Pair<String, String>> {
        val body = event.optString("requestBody", "").trim()
        if (body.isBlank()) return emptyList()
        val mime = event.optString("requestMimeType", "").lowercase(Locale.US)
        if (mime.contains("x-www-form-urlencoded") || (body.contains('=') && body.contains('&') && !body.startsWith("{") && !body.startsWith("["))) {
            return body.split('&').filter { it.isNotBlank() }.map { part ->
                val p = part.indexOf('=')
                urlDecode(if (p >= 0) part.substring(0, p) else part) to
                    urlDecode(if (p >= 0) part.substring(p + 1) else "")
            }
        }
        if (body.startsWith("[[")) {
            try {
                val arr = JSONArray(body)
                val out = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONArray(i) ?: continue
                    if (row.length() >= 2) out.add(row.optString(0) to row.optString(1))
                }
                return out
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    fun buildTimingText(event: JSONObject): String = buildString {
        appendField(this, event, "requestStart", "Request start")
        appendField(this, event, "workerStart", "Worker start")
        appendField(this, event, "responseStart", "Response start")
        appendField(this, event, "responseEnd", "Response end")
        val timing = event.optJSONObject("timing")
        if (timing != null) {
            if (isNotEmpty()) append('\n')
            val keys = timing.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                append(key).append(": ").append(timing.opt(key)).append(" ms\n")
            }
        }
        if (isEmpty()) append("—")
    }.trimEnd()

    fun buildSourcesText(event: JSONObject): String = buildString {
        val sources = NetworkEventClassifier.eventSources(event)
        append("Sources: ").append(if (sources.isEmpty()) "—" else orderedSourceLabel(sources)).append('\n')
        if (sources.size > 1) {
            append("Count: ").append(sources.size).append('\n')
            append("Merge confidence: ").append(if (event.optString("_mergeConfidence") == "MEDIUM") "approximate (~)" else "high (✓)").append('\n')
        }
        if (event.optBoolean("_deduplicated", false)) append("Duplicate groups collapsed: yes\n")
        val fields = listOf(
            "url", "method", "requestHeaders", "requestBody", "status", "responseHeaders", "responseBody",
            "mimeType", "finalUrl", "duration", "httpVersion", "transferSize", "encodedBodySize",
            "decodedBodySize", "cache", "timing", "initiatorStack"
        )
        val present = fields.filter { event.has(it) }
        if (present.isNotEmpty()) {
            append("\nFIELD ORIGIN\n")
            present.forEach { field -> append(field).append(": ").append(fieldOrigin(event, field)).append('\n') }
        }
    }.trimEnd()

    fun buildRequestText(event: JSONObject, cookies: String): String = buildString {
        append(buildRequestSummary(event))
        append("\n\nQUERY PARAMETERS\n").append(formatPairs(queryPairs(event.optString("url", ""))))
        append("\n\nREQUEST HEADERS\n").append(formatHeaders(requestHeaderPairs(event)))
        append("\n\nREQUEST COOKIES\n").append(cookies.ifBlank { "—" })
        append("\n\nREQUEST BODY\n")
        val body = event.optString("requestBody", "")
        append(if (body.isBlank()) "—" else prettyBody(body, event.optString("requestMimeType", "")))
        append("\n\nTIMING\n").append(buildTimingText(event))
    }

    fun buildResponseCopy(event: JSONObject, cookies: String): String = buildString {
        append(buildResponseSummary(event))
        append("\n\nRESPONSE HEADERS\n").append(formatHeaders(responseHeaderPairs(event)))
        append("\n\nCOOKIES FOR URL\n").append(cookies.ifBlank { "—" })
        append("\n\nRESPONSE BODY\n").append(NetworkEventClassifier.responseBodyText(event).ifBlank { "—" })
    }

    fun buildCurl(event: JSONObject): String = buildString {
        val method = NetworkEventClassifier.methodOf(event).ifBlank { "GET" }
        append("curl -X ").append(shellQuote(method)).append(" ").append(shellQuote(event.optString("url", "")))
        requestHeaderPairs(event).forEach { (name, value) -> append(" \\\n  -H ").append(shellQuote("$name: $value")) }
        val body = event.optString("requestBody", "")
        if (body.isNotBlank()) append(" \\\n  --data-raw ").append(shellQuote(body))
    }

    fun formatDuration(ms: Double): String = when {
        ms < 1000.0 -> if (ms % 1.0 == 0.0) "${ms.toLong()} ms" else String.format(Locale.US, "%.1f ms", ms)
        ms < 60000.0 -> String.format(Locale.US, "%.2f s", ms / 1000.0)
        else -> String.format(Locale.US, "%.1f min", ms / 60000.0)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 0L -> "—"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun urlDecode(value: String): String =
        try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }

    fun decodePercentText(raw: String): String {
        var value = raw
        repeat(3) {
            if (!Regex("%[0-9A-Fa-f]{2}").containsMatchIn(value)) return value
            val decoded = try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { return value }
            if (decoded == value) return value
            value = decoded
        }
        return value
    }

    fun prettyBody(raw: String, mime: String): String {
        val t = raw.trim()
        if (t.isBlank()) return "—"
        try {
            if (t.startsWith("{")) return JSONObject(t).toString(2)
            if (t.startsWith("[")) return JSONArray(t).toString(2)
        } catch (_: Exception) {}
        if (mime.contains("json", true)) {
            try { return JSONObject(t).toString(2) } catch (_: Exception) {}
            try { return JSONArray(t).toString(2) } catch (_: Exception) {}
        }
        if (mime.contains("xml", true) || mime.contains("html", true)) return t.replace(Regex(">\\s*<"), ">\n<")
        return raw
    }

    fun isBinaryPayload(mime: String, body: String, bytes: ByteArray): Boolean {
        val m = mime.lowercase(Locale.US)
        if (body == "[non-text response]" || body == "[binary]") return true
        if (m.startsWith("text/") || m.contains("json") || m.contains("javascript") || m.contains("xml") ||
            m.contains("html") || m.contains("css") || m.contains("x-www-form-urlencoded")) return false
        if (m.isNotBlank()) return true
        val sample = bytes.take(512)
        if (sample.any { it.toInt() == 0 }) return true
        val printable = sample.count {
            val n = it.toInt() and 255
            n == 9 || n == 10 || n == 13 || n in 32..126 || n >= 160
        }
        return sample.isNotEmpty() && printable.toDouble() / sample.size < 0.82
    }

    fun suggestFileName(event: JSONObject, mime: String): String {
        val headers = event.optJSONObject("responseHeaders")
        val disposition = headerValue(headers, "Content-Disposition")
        Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)
            ?.let { return sanitizeName(urlDecode(it.trim())) }
        val url = event.optString("finalUrl", event.optString("url", ""))
        val path = try { URL(url).path.substringAfterLast('/') } catch (_: Exception) { "" }
        var name = path.ifBlank { "response" }
        if (!name.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';'))
                ?.takeIf { it.isNotBlank() }?.let { name = "$name.$it" }
        }
        return sanitizeName(name)
    }

    fun headerValue(headers: JSONObject?, name: String): String {
        if (headers == null) return ""
        val iterator = headers.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key.equals(name, true)) return headers.optString(key, "")
        }
        return ""
    }

    private fun objectHeaderPairs(headers: JSONObject?): List<Pair<String, String>> {
        if (headers == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out.add(key to headers.opt(key).toString())
        }
        return out.sortedBy { it.first.lowercase(Locale.US) }
    }

    private fun rawHeaderPairs(raw: String): List<Pair<String, String>> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val split = line.indexOf(':')
            if (split <= 0) null else line.substring(0, split).trim().takeIf { it.isNotBlank() }?.let { name ->
                name to line.substring(split + 1).trim()
            }
        }
    }

    private fun fieldOrigin(event: JSONObject, field: String): String {
        val direct = event.optJSONObject("_fieldSources")?.optString(field, "").orEmpty()
        if (direct.isNotBlank()) return direct
        val merged = event.optJSONArray("_mergedEvents")
        if (merged != null) {
            val origins = linkedSetOf<String>()
            for (i in 0 until merged.length()) {
                val e = merged.optJSONObject(i) ?: continue
                if (!e.has(field)) continue
                val origin = e.optJSONObject("_fieldSources")?.optString(field, "").orEmpty().ifBlank { e.optString("source", "") }
                if (origin.isNotBlank()) origins.add(origin)
            }
            if (origins.isNotEmpty()) return orderedSourceLabel(origins)
        }
        val sources = NetworkEventClassifier.eventSources(event)
        val preferred = when (field) {
            "duration", "httpVersion", "transferSize", "encodedBodySize", "decodedBodySize", "cache", "timing" ->
                listOf("resource-timing", "navigation-timing", "fetch", "xhr", "resource-copy")
            "requestBody", "responseBody", "status", "responseHeaders", "mimeType", "finalUrl" ->
                listOf("fetch", "xhr", "replay", "resource-copy", "webview")
            "requestHeaders" -> listOf("fetch", "xhr", "replay", "webview", "resource-copy")
            "initiatorStack" -> listOf("fetch", "xhr")
            else -> listOf("webview", "fetch", "xhr", "replay", "resource-timing", "resource-copy")
        }
        return preferred.firstOrNull { it in sources } ?: displaySource(event).ifBlank { "—" }
    }

    private fun orderedSourceLabel(sources: Set<String>): String {
        val ordered = displaySourceOrder.filter { it in sources }.toMutableList()
        sources.filter { it !in ordered }.sorted().forEach(ordered::add)
        return ordered.joinToString(" + ")
    }

    private fun displaySource(event: JSONObject): String =
        event.optString("_displaySources", event.optString("source", ""))

    private fun appendUrlBasics(out: StringBuilder, raw: String) {
        try {
            val u = URL(raw)
            out.append("Scheme: ").append(u.protocol).append('\n')
            out.append("Host: ").append(u.host).append('\n')
            out.append("Port: ").append(if (u.port > 0) u.port else u.defaultPort).append('\n')
            out.append("Path: ").append(u.path.ifBlank { "/" }).append('\n')
        } catch (_: Exception) {}
    }

    private fun appendField(out: StringBuilder, event: JSONObject, key: String, label: String) {
        if (event.has(key)) {
            val value = event.opt(key)
            if (value != null && value != JSONObject.NULL && value.toString().isNotBlank()) {
                out.append(label).append(": ").append(value).append('\n')
            }
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun sanitizeName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).ifBlank { "response.bin" }
}
