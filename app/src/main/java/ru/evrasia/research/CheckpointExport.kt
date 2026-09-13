package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject

internal object CheckpointExport {
    fun buildIndex(archive: ResearchArchive): JSONObject {
        val items = JSONArray()
        checkpointRecords(archive).forEach { record ->
            val checkpointId = record.optString("checkpointId", "")
            if (checkpointId.isBlank()) return@forEach
            items.put(
                JSONObject()
                    .put("checkpointId", checkpointId)
                    .put("eventId", record.optString("eventId", ""))
                    .put("sequence", record.optLong("sequence", 0L))
                    .put("time", record.optLong("time", 0L))
                    .put("reason", record.optString("reason", ""))
                    .put("page", record.optString("page", record.optString("url", "")))
                    .put("stateArtifact", record.optString("stateArtifact", ""))
                    .put("screenshotArtifact", record.optString("screenshotArtifact", ""))
                    .put("relatedActionId", record.optString("relatedActionId", ""))
                    .put("relatedRequestId", record.optString("relatedRequestId", ""))
            )
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("format", "web-research-checkpoints-v1")
            .put("sessionId", archive.forensicSessionId)
            .put("generatedAt", System.currentTimeMillis())
            .put("count", items.length())
            .put("items", items)
    }

    fun buildDiffs(archive: ResearchArchive): JSONObject {
        val records = checkpointRecords(archive)
        val diffs = JSONArray()
        var previousRecord: JSONObject? = null
        var previousState: JSONObject? = null

        for (record in records) {
            val state = stateFor(archive, record) ?: continue
            val beforeRecord = previousRecord
            val beforeState = previousState
            if (beforeRecord != null && beforeState != null) {
                diffs.put(
                    JSONObject()
                        .put("fromCheckpointId", beforeRecord.optString("checkpointId", ""))
                        .put("toCheckpointId", record.optString("checkpointId", ""))
                        .put("fromReason", beforeRecord.optString("reason", ""))
                        .put("toReason", record.optString("reason", ""))
                        .put("fromTime", beforeRecord.optLong("time", 0L))
                        .put("toTime", record.optLong("time", 0L))
                        .put("documentCookies", diffFlat(cookieMap(beforeState.optString("cookie", "")), cookieMap(state.optString("cookie", ""))))
                        .put("nativeCookies", diffFlat(cookieMap(beforeState.optString("nativeCookie", "")), cookieMap(state.optString("nativeCookie", ""))))
                        .put("localStorage", diffFlat(storageMap(beforeState.optJSONObject("localStorage")), storageMap(state.optJSONObject("localStorage"))))
                        .put("sessionStorage", diffFlat(storageMap(beforeState.optJSONObject("sessionStorage")), storageMap(state.optJSONObject("sessionStorage"))))
                        .put("dom", diffFlat(domMap(beforeState), domMap(state)))
                        .put("formValues", diffFlat(runtimeValueMap(beforeState), runtimeValueMap(state)))
                        .put("formChecked", diffFlat(runtimeCheckedMap(beforeState), runtimeCheckedMap(state)))
                        .put("formSelected", diffFlat(runtimeSelectedMap(beforeState), runtimeSelectedMap(state)))
                        .put("focus", diffFlat(jsonObjectMap(beforeState.optJSONObject("focus")), jsonObjectMap(state.optJSONObject("focus"))))
                        .put("selection", diffFlat(jsonObjectMap(beforeState.optJSONObject("selection")), jsonObjectMap(state.optJSONObject("selection"))))
                        .put("viewport", diffFlat(jsonObjectMap(beforeState.optJSONObject("viewport")), jsonObjectMap(state.optJSONObject("viewport"))))
                        .put("frames", diffFlat(frameMap(beforeState), frameMap(state)))
                        .put("shadowDom", diffFlat(shadowMap(beforeState), shadowMap(state)))
                )
            }
            previousRecord = record
            previousState = state
        }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("format", "web-research-checkpoint-diffs-v1")
            .put("sessionId", archive.forensicSessionId)
            .put("generatedAt", System.currentTimeMillis())
            .put("diffs", diffs)
    }

    private fun checkpointRecords(archive: ResearchArchive): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        synchronized(archive) {
            for (index in 0 until archive.records.length()) {
                val record = archive.records.optJSONObject(index) ?: continue
                if (record.optString("source", "") == "checkpoint") out.add(record)
            }
        }
        return out.sortedBy { it.optLong("sequence", Long.MAX_VALUE) }
    }

    private fun stateFor(archive: ResearchArchive, record: JSONObject): JSONObject? {
        val path = record.optString("stateArtifact", "")
        if (path.isBlank()) return null
        val bytes = archive.extraArtifacts[path] ?: return null
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }

    private fun storageMap(wrapper: JSONObject?): Map<String, String> {
        val values = wrapper?.optJSONObject("values") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = values.optString(key, "")
        }
        return out
    }

    private fun cookieMap(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        raw.split(';').forEach { part ->
            val item = part.trim()
            if (item.isEmpty()) return@forEach
            val split = item.indexOf('=')
            val name = if (split >= 0) item.substring(0, split).trim() else item
            if (name.isBlank()) return@forEach
            out[name] = if (split >= 0) item.substring(split + 1) else ""
        }
        return out
    }

    private fun runtimeValueMap(state: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        domElements(state).forEachIndexed { index, element ->
            val runtime = element.optJSONObject("runtime") ?: return@forEachIndexed
            if (!runtime.has("value")) return@forEachIndexed
            out[element.optString("key", "").ifBlank { "index:$index" }] = runtime.optString("value", "")
        }
        return out
    }

    private fun runtimeCheckedMap(state: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        domElements(state).forEachIndexed { index, element ->
            val runtime = element.optJSONObject("runtime") ?: return@forEachIndexed
            if (!runtime.has("checked") && !runtime.has("indeterminate")) return@forEachIndexed
            out[element.optString("key", "").ifBlank { "index:$index" }] = JSONObject()
                .apply {
                    if (runtime.has("checked")) put("checked", runtime.optBoolean("checked"))
                    if (runtime.has("indeterminate")) put("indeterminate", runtime.optBoolean("indeterminate"))
                }
                .toString()
        }
        return out
    }

    private fun runtimeSelectedMap(state: JSONObject): Map<String, String> {
        val out = linkedMapOf<String, String>()
        domElements(state).forEachIndexed { index, element ->
            val runtime = element.optJSONObject("runtime") ?: return@forEachIndexed
            if (!runtime.has("selected") && !runtime.has("selectedIndex") && !runtime.has("selectedValues")) return@forEachIndexed
            out[element.optString("key", "").ifBlank { "index:$index" }] = JSONObject()
                .apply {
                    if (runtime.has("selected")) put("selected", runtime.optBoolean("selected"))
                    if (runtime.has("selectedIndex")) put("selectedIndex", runtime.optInt("selectedIndex"))
                    runtime.optJSONArray("selectedValues")?.let { put("selectedValues", JSONArray(it.toString())) }
                }
                .toString()
        }
        return out
    }

    private fun domElements(state: JSONObject): List<JSONObject> {
        val elements = state.optJSONObject("dom")?.optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (index in 0 until elements.length()) elements.optJSONObject(index)?.let(out::add)
        return out
    }

    private fun frameMap(state: JSONObject): Map<String, String> {
        val items = state.optJSONObject("frames")?.optJSONArray("items") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            out[item.optString("key", "").ifBlank { "index:$index" }] = item.toString()
        }
        return out
    }

    private fun shadowMap(state: JSONObject): Map<String, String> {
        val roots = state.optJSONObject("shadowDom")?.optJSONArray("roots") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (index in 0 until roots.length()) {
            val root = roots.optJSONObject(index) ?: continue
            val host = root.optJSONObject("host")
            val key = host?.optString("key", "").orEmpty().ifBlank { "index:$index" }
            out[key] = root.toString()
        }
        return out
    }

    private fun jsonObjectMap(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = value.opt(key)?.toString().orEmpty()
        }
        return out
    }

    private fun domMap(state: JSONObject): Map<String, String> {
        val elements = state.optJSONObject("dom")?.optJSONArray("elements") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val key = element.optString("key", "").ifBlank { "index:$index" }
            out[key] = element.toString()
        }
        return out
    }

    private fun diffFlat(before: Map<String, String>, after: Map<String, String>): JSONObject {
        val beforeKeys = before.keys
        val afterKeys = after.keys
        val added = afterKeys.filter { it !in beforeKeys }.sorted()
        val removed = beforeKeys.filter { it !in afterKeys }.sorted()
        val changed = beforeKeys.intersect(afterKeys).filter { before[it] != after[it] }.sorted()
        val details = JSONArray()
        added.forEach { key -> details.put(JSONObject().put("key", key).put("before", JSONObject.NULL).put("after", after[key] ?: "")) }
        removed.forEach { key -> details.put(JSONObject().put("key", key).put("before", before[key] ?: "").put("after", JSONObject.NULL)) }
        changed.forEach { key -> details.put(JSONObject().put("key", key).put("before", before[key] ?: "").put("after", after[key] ?: "")) }
        return JSONObject()
            .put("added", JSONArray(added))
            .put("removed", JSONArray(removed))
            .put("changed", JSONArray(changed))
            .put("changedCount", added.size + removed.size + changed.size)
            .put("details", details)
    }
}
