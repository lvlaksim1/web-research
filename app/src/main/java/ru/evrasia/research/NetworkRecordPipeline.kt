package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit boundary between the raw research archive and the correlated debugger view.
 * Raw records are preserved untouched. The debugger receives an independent projection with
 * bounded large bodies so diagnostics cannot multiply multi-megabyte payloads in memory.
 */
internal object NetworkRecordPipeline {
    private const val MAX_DEBUG_RESPONSE_BODY_CHARS = 512 * 1024
    private const val MAX_DEBUG_REQUEST_BODY_CHARS = 128 * 1024

    fun appendRawAndDebug(rawRecords: JSONArray, record: JSONObject) {
        rawRecords.put(record)
        NetworkDebugStore.add(normalizeForDebugger(record))
    }

    fun addDebuggerOnly(record: JSONObject) {
        NetworkDebugStore.add(normalizeForDebugger(record))
    }

    fun clearDebugger() {
        NetworkDebugStore.clear()
    }

    private fun normalizeForDebugger(record: JSONObject): JSONObject {
        val copy = copyObject(record)

        val legacy = copy.optJSONObject("headers")
        val request = copy.optJSONObject("requestHeaders") ?: JSONObject()
        if (legacy != null) {
            val keys = legacy.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!hasHeader(request, key)) request.put(key, legacy.opt(key))
            }
        }
        if (request.length() > 0) copy.put("requestHeaders", request)
        return copy
    }

    private fun copyObject(source: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = source.opt(key)
            when {
                key == "responseBody" && value is String ->
                    putBounded(out, key, value, MAX_DEBUG_RESPONSE_BODY_CHARS)
                key == "requestBody" && value is String ->
                    putBounded(out, key, value, MAX_DEBUG_REQUEST_BODY_CHARS)
                value is JSONObject -> out.put(key, copyObject(value))
                value is JSONArray -> out.put(key, copyArray(value))
                value != null -> out.put(key, value)
            }
        }
        return out
    }

    private fun copyArray(source: JSONArray): JSONArray {
        val out = JSONArray()
        for (index in 0 until source.length()) {
            when (val value = source.opt(index)) {
                is JSONObject -> out.put(copyObject(value))
                is JSONArray -> out.put(copyArray(value))
                else -> out.put(value)
            }
        }
        return out
    }

    private fun putBounded(target: JSONObject, key: String, value: String, limit: Int) {
        if (value.length <= limit) {
            target.put(key, value)
            return
        }
        target.put(key, value.substring(0, limit))
        target.put("${key}Truncated", true)
        target.put("${key}OriginalChars", value.length)
    }

    private fun hasHeader(headers: JSONObject, name: String): Boolean {
        val keys = headers.keys()
        while (keys.hasNext()) if (keys.next().equals(name, true)) return true
        return false
    }
}
