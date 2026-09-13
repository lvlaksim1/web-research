package ru.evrasia.research

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ResearchArchive internal constructor(
    sessionId: String = java.util.UUID.randomUUID().toString(),
    sessionStartedAtMs: Long = System.currentTimeMillis()
) {
    private var forensicTimeline = ForensicTimeline(sessionId, sessionStartedAtMs)
    internal val forensicSessionId: String get() = forensicTimeline.sessionId
    internal val forensicSessionStartedAtMs: Long get() = forensicTimeline.sessionStartedAtMs
    val records = JSONArray()
    val scripts = ConcurrentHashMap<String, ByteArray>()
    val scriptErrors = ConcurrentHashMap<String, String>()
    val resources = ConcurrentHashMap<String, ByteArray>()
    val resourceMeta = ConcurrentHashMap<String, JSONObject>()
    val extraArtifacts = ConcurrentHashMap<String, ByteArray>()
    private val recordCapturedAt = mutableListOf<Long>()
    private val scriptCapturedAt = ConcurrentHashMap<String, Long>()
    private val scriptErrorCapturedAt = ConcurrentHashMap<String, Long>()
    private val resourceCapturedAt = ConcurrentHashMap<String, Long>()
    private val resourceMetaCapturedAt = ConcurrentHashMap<String, Long>()
    private val artifactCapturedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var snapshotCapturedAt = 0L
    @Volatile var snapshot = JSONObject()

    @Synchronized fun addRecord(record: JSONObject) {
        forensicTimeline.annotate(record)
        NetworkRecordPipeline.appendRawAndDebug(records, record)
        recordCapturedAt.add(System.currentTimeMillis())
    }

    @Synchronized fun addCheckpoint(reason: String, state: JSONObject, screenshot: ByteArray?): String {
        val capturedAt = System.currentTimeMillis()
        val event = JSONObject()
            .put("source", "checkpoint")
            .put("time", state.optLong("time", capturedAt))
            .put("reason", reason)
            .put("page", state.optString("url", ""))
            .put("title", state.optString("title", ""))

        state.optJSONObject("trigger")?.let { trigger ->
            if (trigger.has("method")) event.put("method", trigger.optString("method", ""))
            if (trigger.has("url")) event.put("url", trigger.optString("url", ""))
            if (trigger.has("status")) event.put("status", trigger.optInt("status", 0))
            if (trigger.has("action")) event.put("action", trigger.optString("action", ""))
        }

        forensicTimeline.annotate(event)
        val checkpointId = event.getString("checkpointId")
        val statePath = "checkpoints/$checkpointId/state.json"
        val screenshotPath = if (screenshot != null) "checkpoints/$checkpointId/screenshot.jpg" else ""

        state.put("checkpointId", checkpointId)
        state.put("reason", reason)
        event.put("stateArtifact", statePath)
        if (screenshotPath.isNotBlank()) event.put("screenshotArtifact", screenshotPath)

        NetworkRecordPipeline.appendRawAndDebug(records, event)
        recordCapturedAt.add(capturedAt)

        artifactCapturedAt[statePath] = capturedAt
        extraArtifacts[statePath] = state.toString().toByteArray(Charsets.UTF_8)
        if (screenshot != null) {
            artifactCapturedAt[screenshotPath] = capturedAt
            extraArtifacts[screenshotPath] = screenshot
        }
        return checkpointId
    }

    fun putScript(url: String, bytes: ByteArray) {
        scriptCapturedAt[url] = System.currentTimeMillis()
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
        scriptErrorCapturedAt[url] = System.currentTimeMillis()
        scriptErrors[url] = error
    }

    fun putResource(url: String, bytes: ByteArray, meta: JSONObject) {
        val capturedAt = System.currentTimeMillis()
        resourceCapturedAt[url] = capturedAt
        resourceMetaCapturedAt[url] = capturedAt
        resources[url] = bytes
        resourceMeta[url] = meta
    }

    fun putResourceMeta(url: String, meta: JSONObject) {
        resourceMetaCapturedAt[url] = System.currentTimeMillis()
        resourceMeta[url] = meta
    }

    fun putArtifact(key: String, bytes: ByteArray) {
        artifactCapturedAt[key] = System.currentTimeMillis()
        extraArtifacts[key] = bytes
    }

    fun updateSnapshot(value: JSONObject) {
        snapshotCapturedAt = System.currentTimeMillis()
        snapshot = value
    }

    @Synchronized fun snapshotWindow(startedAt: Long, endedAt: Long): ResearchArchive {
        val out = ResearchArchive(forensicTimeline.sessionId, forensicTimeline.sessionStartedAtMs)
        for (index in 0 until records.length()) {
            val capturedAt = recordCapturedAt.getOrNull(index) ?: continue
            if (capturedAt in startedAt..endedAt) {
                records.optJSONObject(index)?.let { out.records.put(JSONObject(it.toString())) }
            }
        }

        // Scripts/resources are supporting evidence for requests inside the recording window.
        // Keep anything already captured in the current browser session before the window ended,
        // even if it was first loaded a few seconds before the user pressed "record".
        scripts.forEach { (key, value) ->
            val capturedAt = scriptCapturedAt[key] ?: return@forEach
            if (capturedAt <= endedAt) out.scripts[key] = value.copyOf()
        }
        scriptErrors.forEach { (key, value) ->
            val capturedAt = scriptErrorCapturedAt[key] ?: return@forEach
            if (capturedAt <= endedAt) out.scriptErrors[key] = value
        }
        resources.forEach { (key, value) ->
            val capturedAt = resourceCapturedAt[key] ?: return@forEach
            if (capturedAt <= endedAt) out.resources[key] = value.copyOf()
        }
        resourceMeta.forEach { (key, value) ->
            val capturedAt = resourceMetaCapturedAt[key] ?: return@forEach
            if (capturedAt <= endedAt) out.resourceMeta[key] = JSONObject(value.toString())
        }
        extraArtifacts.forEach { (key, value) ->
            if (key == "cookie-trace.json") {
                filterCookieTraceWindow(value, startedAt, endedAt)?.let { out.extraArtifacts[key] = it }
                return@forEach
            }
            val capturedAt = artifactCapturedAt[key] ?: return@forEach
            if (capturedAt in startedAt..endedAt) out.extraArtifacts[key] = value.copyOf()
        }

        out.snapshot = try { JSONObject(snapshot.toString()) } catch (_: Exception) { JSONObject() }
        out.snapshotCapturedAt = snapshotCapturedAt
        return out
    }

    private fun filterCookieTraceWindow(bytes: ByteArray, startedAt: Long, endedAt: Long): ByteArray? {
        return try {
            val source = JSONObject(bytes.toString(Charsets.UTF_8))
            val events = source.optJSONArray("events") ?: JSONArray()
            val filtered = JSONArray()
            for (index in 0 until events.length()) {
                val event = events.optJSONObject(index) ?: continue
                val time = event.optLong("time", Long.MIN_VALUE)
                if (time in startedAt..endedAt) filtered.put(JSONObject(event.toString()))
            }
            JSONObject(source.toString())
                .put("events", filtered)
                .put("windowStartedAt", startedAt)
                .put("windowEndedAt", endedAt)
                .toString(2)
                .toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
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
        recordCapturedAt.clear()
        scriptCapturedAt.clear()
        scriptErrorCapturedAt.clear()
        resourceCapturedAt.clear()
        resourceMetaCapturedAt.clear()
        artifactCapturedAt.clear()
        snapshotCapturedAt = 0L
        snapshot = JSONObject()
        forensicTimeline = ForensicTimeline()
        NetworkRecordPipeline.clearDebugger()
    }

}
