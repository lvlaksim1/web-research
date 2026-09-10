package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ResearchArchive {
    val records = JSONArray()
    val scripts = ConcurrentHashMap<String, ByteArray>()
    val scriptErrors = ConcurrentHashMap<String, String>()
    val resources = ConcurrentHashMap<String, ByteArray>()
    val resourceMeta = ConcurrentHashMap<String, JSONObject>()
    val extraArtifacts = ConcurrentHashMap<String, ByteArray>()
    @Volatile var snapshot = JSONObject()

    @Synchronized fun addRecord(record: JSONObject) {
        NetworkRecordPipeline.appendRawAndDebug(records, record)
    }

    fun putScript(url: String, bytes: ByteArray) {
        val previous = scripts.put(url, bytes)
        if (previous == null && isInlineScript(url)) {
            NetworkRecordPipeline.addDebuggerOnly(JSONObject()
                .put("source", "js-file")
                .put("time", System.currentTimeMillis())
                .put("method", "JS")
                .put("url", url)
                .put("mimeType", "application/javascript")
                .put("responseSize", bytes.size)
                .put("responseBody", try { bytes.toString(Charsets.UTF_8) } catch (_: Exception) { "[binary]" }))
        }
    }

    fun putScriptError(url: String, error: String) {
        scriptErrors[url] = error
    }

    fun putResource(url: String, bytes: ByteArray, meta: JSONObject) {
        resources[url] = bytes
        resourceMeta[url] = meta
    }

    fun putResourceMeta(url: String, meta: JSONObject) {
        resourceMeta[url] = meta
    }

    fun putArtifact(key: String, bytes: ByteArray) {
        extraArtifacts[key] = bytes
    }

    fun updateSnapshot(value: JSONObject) {
        snapshot = value
    }

    private fun isInlineScript(url: String): Boolean =
        (!url.startsWith("http://") && !url.startsWith("https://")) || url.contains("#inline-")

    @Synchronized fun clear() {
        while (records.length() > 0) records.remove(records.length() - 1)
        scripts.clear()
        scriptErrors.clear()
        resources.clear()
        resourceMeta.clear()
        extraArtifacts.clear()
        snapshot = JSONObject()
        NetworkRecordPipeline.clearDebugger()
    }

}
